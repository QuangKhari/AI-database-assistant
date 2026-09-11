import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { chatApi } from "../api/chatApi";
import { connectionApi } from "../api/connectionApi";
import { ToastProvider } from "../context/ToastContext";
import { ChatPage } from "./ChatPage";

vi.mock("../api/connectionApi", () => ({
  connectionApi: { list: vi.fn(), suggestedQuestions: vi.fn() },
}));
vi.mock("../api/chatApi", () => ({
  chatApi: {
    conversations: vi.fn(),
    conversationsPaged: vi.fn(),
    messages: vi.fn(),
    preview: vi.fn(),
    execute: vi.fn(),
    removeConversation: vi.fn(),
    exportExcel: vi.fn(),
  },
}));

describe("ChatPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(connectionApi.list).mockResolvedValue([
      {
        id: 2,
        name: "Shop",
        dbType: "mysql",
        host: "localhost",
        port: 3306,
        databaseName: "shop",
        username: "reader",
        sslEnabled: false,
        active: true,
        lastTestedAt: null,
        lastTestSuccessful: true,
        createdAt: "",
        updatedAt: "",
      },
    ]);
    vi.mocked(connectionApi.suggestedQuestions).mockResolvedValue({
      questions: [],
      source: "template",
    });
    vi.mocked(chatApi.conversations).mockResolvedValue([]);
    vi.mocked(chatApi.conversationsPaged).mockResolvedValue({
      content: [],
      totalElements: 0,
      totalPages: 1,
      number: 0,
      size: 8,
    });
    // Khớp PreviewResponse.java thật: generatedSql / valid / errorMessage.
    vi.mocked(chatApi.preview).mockResolvedValue({
      generatedSql: "SELECT * FROM orders",
      valid: true,
      errorMessage: null,
    });
  });

  it("sends a question and renders the safe SQL preview", async () => {
    render(
      <ToastProvider>
        <ChatPage />
      </ToastProvider>,
    );
    const input = await screen.findByPlaceholderText(
      "Hỏi bằng tiếng Việt hoặc tiếng Anh…",
    );
    await userEvent.type(input, "Liệt kê đơn hàng");
    await userEvent.click(
      screen.getByRole("button", { name: "Tạo SQL preview" }),
    );

    // ChatPage gửi đúng payload theo QueryRequest.java: databaseConnectionId + question.
    await waitFor(() =>
      expect(chatApi.preview).toHaveBeenCalledWith({
        databaseConnectionId: 2,
        question: "Liệt kê đơn hàng",
      }),
    );
    expect(await screen.findByText("SELECT * FROM orders")).toBeInTheDocument();
    expect(screen.getByText("SQL hợp lệ")).toBeInTheDocument();
  });

  it("lets the user paginate the conversations sidebar", async () => {
    vi.mocked(chatApi.conversationsPaged).mockImplementation(
      async (_connectionId, page) =>
        page === 0
          ? {
              content: [
                {
                  id: 1,
                  title: "Đơn hàng tháng 8",
                  connectionId: 2,
                  createdAt: "",
                  updatedAt: "2026-08-01T00:00:00",
                },
              ],
              totalElements: 2,
              totalPages: 2,
              number: 0,
              size: 8,
            }
          : {
              content: [
                {
                  id: 2,
                  title: "Doanh thu quý 2",
                  connectionId: 2,
                  createdAt: "",
                  updatedAt: "2026-05-01T00:00:00",
                },
              ],
              totalElements: 2,
              totalPages: 2,
              number: 1,
              size: 8,
            },
    );

    render(
      <ToastProvider>
        <ChatPage />
      </ToastProvider>,
    );

    expect(await screen.findByText("Đơn hàng tháng 8")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Sau ›" }));

    await waitFor(() =>
      expect(chatApi.conversationsPaged).toHaveBeenCalledWith(2, 1, 8),
    );
    expect(await screen.findByText("Doanh thu quý 2")).toBeInTheDocument();
  });
});
