# 🤖 Herobot

Herobot is a cross-platform Java-based chatbot that combines traditional keyword matching (using Cosine Similarity) with local LLM capabilities via Ollama. It uses SQLite for persistent storage of training data.

## ✨ Features
- **Hybrid Intelligence**: Uses vector-based similarity for fast local responses and falls back to a local LLM for complex queries.
- **Cross-Platform**: Compatible with both Windows and Linux using provided Gradle wrappers.
- **Training Mode**: Easily teach the bot new responses directly from the terminal.
- **SQLite Integration**: Automatically saves and loads question-answer pairs.
- **UTF-8 Support**: Properly handles special characters (e.g., Islamic symbols like ﷺ).

## 📋 Prerequisites
1. **Java JDK 17 or higher** installed.
2. **Ollama** (Optional, for LLM support):
   - Install Ollama from [ollama.com](https://ollama.com).
   - Run the Phi model: `ollama run phi`.

## 🚀 Installation & Setup

1. **Clone the repository**:
   ```bash
   git clone <your-repo-url>
   cd Herobot
   ```

2. **Prepare the database**:
   The bot will automatically create `chatbot.db` and import `chatbot_training_data.txt` on the first run.

## 💻 How to Run

### On Windows
Open Command Prompt or PowerShell in the project folder and run:
```cmd
gradlew.bat run
```

### On Linux / macOS
Open a terminal in the project folder and run:
```bash
chmod +x gradlew
./gradlew run
```

## 🛠️ Usage
Once the bot is running, you can:
- **Chat**: Simply type your question and press Enter.
- **Train**: Type `train` to enter training mode and add a new custom response.
- **Exit**: Type `exit` to close the application.

## 🧠 Technical Details
- **Similarity Logic**: The bot converts text into vectors and uses **Cosine Similarity** to find the closest match in the database.
- **Fallback**: If the similarity score is below `0.55`, the query is sent to a local Ollama instance (running the `phi` model) at `http://localhost:11434`.
- **Dependencies**:
  - `org.xerial:sqlite-jdbc`
  - `org.apache.commons:commons-math3`
  - `org.json:json`

## 📝 License
GNU GENERAL PUBLIC LICENSE
Version 3, 29 June 2007

 Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>
 Everyone is permitted to copy and distribute verbatim copies
 of this license document, but changing it is not allowed.
---
*Created with ❤️ by HubakaPC*
