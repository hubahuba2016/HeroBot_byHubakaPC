import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
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
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealVector;
import org.json.JSONObject;


public class Chatbot {
    private static final String DB_NAME = "chatbot.db";

    private static final Set<String> STOPWORDS = Set.of(
    "what","is","the","a","an","are","you","can","to","of","and","in","on","at","for","with",
    "who","why","how","when","where"
    );


  public static void main(String[] args) throws Exception {
        try (Connection conn = connectDB()) {
            importTrainingData(conn, "chatbot_training_data.txt");
            Scanner scanner = new Scanner(System.in);
            System.out.println("📱 Simple Chatbot (Java + SQLite)");
            System.out.println("Type 'train' to teach, 'exit' to quit.\n");

            while (true) {
                System.out.print("You: ");
                String input = scanner.nextLine().trim().toLowerCase();
                if (input.equals("exit")) break;
                else if (input.equals("train")) {
                    trainBot(conn, scanner);
                } else {
                    System.out.println("Bot: " + getResponse(conn, input));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

        private static Connection connectDB() throws SQLException, ClassNotFoundException {
    Class.forName("org.sqlite.JDBC"); // <-- ADD THIS LINE
    Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_NAME);
    try (Statement stmt = conn.createStatement()) {
        stmt.executeUpdate(
            "CREATE TABLE IF NOT EXISTS chatbot (\n" +
            "    question TEXT PRIMARY KEY,\n" +
            "    answer TEXT NOT NULL\n" +
            ")"
        );
    }
    return conn;
}

    private static void importTrainingData(Connection conn, String filename) throws IOException, SQLException {
        File file = new File(filename);
      if (!file.exists()) {
    System.out.println("Training file '" + filename + "' not found in: " + file.getAbsolutePath());
    return;
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        String line;
        String question = null, answer = null;
        PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO chatbot (question, answer) VALUES (?, ?)");

        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("Q: ")) question = line.substring(3).trim().toLowerCase();
            else if (line.startsWith("A: ")) {
                answer = line.substring(3).trim();
                if (question != null && answer != null) {
                    ps.setString(1, question);
                    ps.setString(2, answer);
                    ps.executeUpdate();
                    question = answer = null;
                }
            }
        }

        reader.close();
        System.out.println("Training data imported successfully.");
    }

    private static void trainBot(Connection conn, Scanner scanner) throws SQLException {
        System.out.println("Training mode. Add a new question-answer pair.");
        System.out.print("Question: ");
        String question = scanner.nextLine().trim().toLowerCase();
        System.out.print("Answer: ");
        String answer = scanner.nextLine().trim();

        if (!question.isEmpty() && !answer.isEmpty()) {
            PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO chatbot (question, answer) VALUES (?, ?)");
            ps.setString(1, question);
            ps.setString(2, answer);
            ps.executeUpdate();
            System.out.println("Training saved.\n");
        } else {
            System.out.println("Empty input! Try again.\n");
        }
    }

  private static String getResponse(Connection conn, String input) throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT question, answer FROM chatbot");

    List<String> questions = new ArrayList<>();
    List<String> answers = new ArrayList<>();
    while (rs.next()) {
        questions.add(rs.getString("question"));
        answers.add(rs.getString("answer"));
    }

    if (questions.isEmpty())
        return "I'm not trained yet. Type 'train' to teach me.";

    List<String> allTexts = new ArrayList<>(questions);
    allTexts.add(input);

    // ✅ Build vocab BEFORE vectorizing
    Map<String, Integer> vocab = buildVocab(allTexts);

    RealVector inputVec = vectorize(input, vocab);

    double bestScore = 0.0;
    String bestAnswer = "I don't know how to respond to that.";

    for (int i = 0; i < questions.size(); i++) {
        RealVector qVec = vectorize(questions.get(i), vocab);
        double sim = cosineSimilarity(inputVec, qVec);

        if (sim > bestScore) {
            bestScore = sim;
            bestAnswer = answers.get(i);
        }
    }

    if (bestScore > 0.55) {
        return bestAnswer;
    } else {
        return askLocalLLM(input);
    }
}


   private static Map<String, Integer> buildVocab(List<String> texts) {
    Set<String> words = new HashSet<>();

    for (String text : texts) {
        text = text.replaceAll("[^a-zA-Z0-9 ]", "");
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
    text = text.replaceAll("[^a-zA-Z0-9 ]", "");

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
            URL url = new URL("http://localhost:11434/api/generate");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            String fullPrompt =
                    "You are HeroBot, an intelligent assistant created by Hubaka.\n" +
                            "Be helpful and concise.\n\n" +
                            "User: " + prompt + "\nAssistant:";

            // ✅ Build JSON properly
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("model", "phi");
            jsonBody.put("prompt", fullPrompt);
            jsonBody.put("stream", false);

            String jsonInput = jsonBody.toString();

            // ✅ Send request
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonInput.getBytes(StandardCharsets.UTF_8));
            }

            // ✅ Read response (safe handling)
            InputStream responseStream;
            if (conn.getResponseCode() >= 400) {
                responseStream = conn.getErrorStream();
            } else {
                responseStream = conn.getInputStream();
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
} else {
    return "LLM error: " + jsonResponse.toString();
}

        } catch (Exception e) {
            e.printStackTrace();
            return "Local LLM connection error.";
        }
    }


}
