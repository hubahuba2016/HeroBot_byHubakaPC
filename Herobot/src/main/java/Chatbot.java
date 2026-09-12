import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealVector;
import org.json.JSONObject;


public class Chatbot {
    private static final String DB_NAME = "chatbot.db";
    private static final double MATCH_THRESHOLD = 0.55;
        private static final String OLLAMA_URL = "http://localhost:11434/api/generate";
        private static final String OLLAMA_MODEL = System.getenv().getOrDefault("HEROBOT_MODEL", "llama3.2:1b");
        private static final Pattern ARITHMETIC_EXPRESSION =
            Pattern.compile("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(-?\\d+(?:\\.\\d+)?)\\s*[?=]?\\s*$");
    private static final String UNKNOWN_RESPONSE =
            "I am not sure yet. You can teach me with 'train', or ask me another way.";

    private static final Set<String> STOPWORDS = Set.of(
            "what", "is", "the", "a", "an", "are", "you", "can", "to", "of", "and", "in", "on", "at", "for", "with",
            "who", "why", "how", "when", "where", "i", "me", "my", "do", "does", "please", "could", "would"
    );

    public static void main(String[] args) {
        try (Connection conn = connectDB()) {
            importTrainingData(conn, "chatbot_training_data.txt");
            try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
                boolean interactiveTerminal = System.console() != null;
                System.out.println("HeroBot (Java + SQLite)");
                System.out.println("Type 'train' to teach, 'help' for commands, or 'exit' to quit.\n");

                while (scanner.hasNextLine()) {
                    if (interactiveTerminal) {
                        System.out.print("You: ");
                    }
                    String input = scanner.nextLine().trim();
                    if (!interactiveTerminal) {
                        System.out.println("You: " + input);
                    }
                    if (input.equalsIgnoreCase("exit")) {
                        break;
                    } else if (input.equalsIgnoreCase("train")) {
                        trainBot(conn, scanner);
                    } else if (input.equalsIgnoreCase("help")) {
                        System.out.println("Bot: Chat normally, type 'train' to add an answer, or 'exit' to quit.");
                    } else if (!input.isEmpty()) {
                        System.out.println("Bot: " + getResponse(conn, input));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("HeroBot could not start: " + e.getMessage());
        }
    }

    private static Connection connectDB() throws SQLException, ClassNotFoundException {
        Class.forName("org.sqlite.JDBC");
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_NAME);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS chatbot (question TEXT PRIMARY KEY, answer TEXT NOT NULL)");
        }
        return conn;
    }

    private static void importTrainingData(Connection conn, String filename) throws IOException, SQLException {
        InputStream input = Chatbot.class.getClassLoader().getResourceAsStream(filename);
        if (input == null) {
            File file = new File(filename);
            if (!file.exists()) {
                System.out.println("Training file not found: " + file.getAbsolutePath());
                return;
            }
            input = new java.io.FileInputStream(file);
        }

        int imported = 0;
        try (InputStream stream = input;
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
             PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO chatbot (question, answer) VALUES (?, ?)")) {
            String question = null;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("Q:")) {
                    question = normalize(line.substring(2));
                } else if (line.startsWith("A:") && question != null) {
                    String answer = line.substring(2).trim();
                    if (!answer.isEmpty()) {
                        ps.setString(1, question);
                        ps.setString(2, answer);
                        ps.executeUpdate();
                        imported++;
                        question = null;
                    }
                }
            }
        }
        System.out.println("Loaded " + imported + " training responses.");
    }

    private static void trainBot(Connection conn, Scanner scanner) throws SQLException {
        System.out.println("Training mode. Add a new question-answer pair.");
        System.out.print("Question: ");
        if (!scanner.hasNextLine()) {
            System.out.println("Training cancelled.\n");
            return;
        }
        String question = normalize(scanner.nextLine());
        System.out.print("Answer: ");
        if (!scanner.hasNextLine()) {
            System.out.println("Training cancelled.\n");
            return;
        }
        String answer = scanner.nextLine().trim();

        if (!question.isEmpty() && !answer.isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO chatbot (question, answer) VALUES (?, ?)")) {
                ps.setString(1, question);
                ps.setString(2, answer);
                ps.executeUpdate();
            }
            System.out.println("Training saved.\n");
        } else {
            System.out.println("Empty input! Try again.\n");
        }
    }

    private static String getResponse(Connection conn, String input) throws SQLException {
        String arithmeticAnswer = solveArithmetic(input);
        if (arithmeticAnswer != null) {
            return arithmeticAnswer;
        }
        input = normalize(input);

        try (PreparedStatement ps = conn.prepareStatement("SELECT answer FROM chatbot WHERE question = ?")) {
            ps.setString(1, input);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("answer");
                }
            }
        }

        List<String> questions = new ArrayList<>();
        List<String> answers = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT question, answer FROM chatbot");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                questions.add(rs.getString("question"));
                answers.add(rs.getString("answer"));
            }
        }

        if (questions.isEmpty()) {
            return "I'm not trained yet. Type 'train' to teach me.";
        }

        List<String> allTexts = new ArrayList<>(questions);
        allTexts.add(input);

        Map<String, Integer> vocab = buildVocab(allTexts);

        RealVector inputVec = vectorize(input, vocab);

        double bestScore = 0.0;
        String bestAnswer = UNKNOWN_RESPONSE;

        for (int i = 0; i < questions.size(); i++) {
            RealVector qVec = vectorize(questions.get(i), vocab);
            double sim = cosineSimilarity(inputVec, qVec);

            if (sim > bestScore) {
                bestScore = sim;
                bestAnswer = answers.get(i);
            }
        }

        if (bestScore >= MATCH_THRESHOLD) {
            return bestAnswer;
        }
        return askLocalLLM(input);
    }

    private static String solveArithmetic(String input) {
        String expression = input.replaceAll("(?i)^(what is|calculate|compute|solve)\\s+", "").trim();
        Matcher matcher = ARITHMETIC_EXPRESSION.matcher(expression);
        if (!matcher.matches()) {
            return null;
        }

        double left = Double.parseDouble(matcher.group(1));
        double right = Double.parseDouble(matcher.group(3));
        double result;
        switch (matcher.group(2)) {
            case "+" -> result = left + right;
            case "-" -> result = left - right;
            case "*" -> result = left * right;
            case "/" -> {
                if (right == 0) {
                    return "Division by zero is undefined.";
                }
                result = left / right;
            }
            default -> { return null; }
        }

        return formatNumber(result);
    }

    private static String formatNumber(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}']", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static Map<String, Integer> buildVocab(List<String> texts) {
        Set<String> words = new HashSet<>();

        for (String text : texts) {
            text = normalize(text);
            for (String word : text.split("\\s+")) {
                if (!STOPWORDS.contains(word) && !word.isBlank()) {
                    words.add(word);
                }
            }
        }

        Map<String, Integer> vocab = new HashMap<>();
        int index = 0;
        for (String word : words) {
            vocab.put(word, index++);
        }

        return vocab;
    }


    private static RealVector vectorize(String text, Map<String, Integer> vocab) {
        text = normalize(text);

        double[] vec = new double[vocab.size()];

        for (String word : text.split("\\s+")) {
            if (!STOPWORDS.contains(word) && vocab.containsKey(word)) {
                vec[vocab.get(word)] += 1.0;
            }
        }

        return new ArrayRealVector(vec);
    }


    private static double cosineSimilarity(RealVector v1, RealVector v2) {
        double dot = v1.dotProduct(v2);
        double norm1 = v1.getNorm();
        double norm2 = v2.getNorm();
        return (norm1 == 0 || norm2 == 0) ? 0.0 : dot / (norm1 * norm2);
    }
    private static String askLocalLLM(String prompt) {
        try {
            URL url = new URL(OLLAMA_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setConnectTimeout(1500);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

                String fullPrompt =
                    "You are HeroBot, an intelligent assistant created by Hubaka.\n" +
                        "Reason carefully before answering. For calculations, verify each operation and show a short explanation.\n" +
                        "If you are uncertain, say so instead of inventing facts. Be helpful and concise.\n\n" +
                        "User: " + prompt + "\nAssistant:";

            // ✅ Build JSON properly
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("model", OLLAMA_MODEL);
            jsonBody.put("prompt", fullPrompt);
            jsonBody.put("stream", false);

            String jsonInput = jsonBody.toString();

            // ✅ Send request
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonInput.getBytes(StandardCharsets.UTF_8));
            }

            // ✅ Read response (safe handling)
            int responseCode = conn.getResponseCode();
            InputStream responseStream = responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (responseStream == null) {
                return "Ollama returned HTTP " + responseCode + ".";
            }

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(responseStream, StandardCharsets.UTF_8)
            );

            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                response.append(line);
            }
            in.close();

            // ✅ Parse JSON safely
            JSONObject jsonResponse = new JSONObject(response.toString());
            if (jsonResponse.has("response")) {
                return jsonResponse.getString("response").trim();
            }
            return UNKNOWN_RESPONSE;

        } catch (Exception e) {
            return "I could not reach Ollama. Start it with 'ollama serve' or teach me this answer with 'train'.";
        }
    }


}
