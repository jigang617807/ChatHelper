# System Function Analysis Report

## Project Overview
This project, **"demo1.0 — Spring Boot RAG 文舱问答,"** is a sophisticated Java-based Retrieval-Augmented Generation (RAG) application. It integrates several modern technologies to provide an end-to-end document-based Q&A experience.

## Core Modules and Functionalities

### 1. User Authentication and Management
- **Registration**: Allows users to create new accounts (`/auth/register`).
- **Login**: Secure user login with session-based authentication (`/auth/login`).
- **Password Reset**: A simulated SMS verification system for password recovery (`/auth/reset`).
- **Profile Management**: Users can update their personal information and upload/change their profile avatars (`/user/profile`).

### 2. Document Lifecycle Management
- **PDF Upload**: Supports local PDF file uploads (`/doc/upload`).
- **Enterprise-Grade Async Processing**: To ensure a smooth user experience, heavy tasks like PDF parsing and vectorization are handled asynchronously:
    - **Message Queue**: Uses **RabbitMQ** to queue document processing tasks.
    - **PDF Parsing**: Uses **PDFBox** to extract text from uploaded files.
    - **Text Splitting**: Uses a recursive text splitter with overlap to ensure context preservation.
    - **Vectorization**: Integrates with **Zhipu AI SDK** to generate high-dimensional embeddings.
- **Status Tracking**: The system tracks the document state: `PENDING` → `PROCESSING` → `COMPLETED` or `FAILED`.
- **Cleanup**: Supports cascading deletion of documents, associated vector chunks, chat histories, and local files.

### 3. RAG-Based Conversational AI
- **Semantic Retrieval**: Uses **PostgreSQL with pgvector** for ultra-fast vector similarity search (`ORDER BY embedding <=> query_vector`) to find the most relevant document chunks for a given question.
- **Context-Aware Q&A**: Combines retrieved document context with user queries to generate accurate responses using **Zhipu AI's Large Language Models (LLM)**.
- **Interactive Chat Interface**:
    - **SSE Streaming**: Responses are delivered in real-time via **Server-Sent Events (SSE)** for a "typing" effect.
    - **Markdown & Code Highlighting**: Supports markdown rendering (marked.js) and syntax highlighting (highlight.js) in responses.
    - **Conversation History**: Tracks and displays previous messages within a session.

## Technology Stack
| Layer | Technologies |
|---|---|
| **Backend** | Spring Boot 3.2, Spring MVC, Spring Data JPA |
| **Database** | PostgreSQL + pgvector |
| **Messaging** | RabbitMQ |
| **AI/LLM** | Zhipu AI SDK (Embedding & Chat) |
| **PDF Processing** | PDFBox |
| **Frontend** | Thymeleaf, Vanilla CSS (Apple-style design available) |

## Implementation Highlights
- **Projection Interface**: Efficiently queries only necessary text data from the database, avoiding performance overhead from transferring large vector columns.
- **State Machine UI**: Provides real-time visual feedback on document processing status.
- **Resource Management**: Ensures proper cleanup of local files and associated database records during document deletion.
