import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { historyApi } from "../api/historyApi";
import { ToastProvider } from "../context/ToastContext";
import { HistoryPage } from "./HistoryPage";

vi.mock("../api/historyApi", () => ({
  historyApi: {
    list: vi.fn(),
    detail: vi.fn(),
    pinned: vi.fn(),
    search: vi.fn(),
    togglePin: vi.fn(),
    remove: vi.fn(),
    removeAll: vi.fn(),
  },
}));

const conversation = {
  id: 1,
  title: "Doanh thu tháng 8",
  connectionId: 2,
  createdAt: "2026-09-01T08:00:00",
  updatedAt: "2026-09-01T08:10:00",
};

const assistantMessage = {
  id: 11,
  role: "assistant" as const,
  content: "Đã trả lời thành công",
  generatedSql: "SELECT SUM(total) FROM orders",
  createdAt: "2026-09-01T08:10:00",
  pinned: false,
  queryLogs: [{
    attemptNumber: 1,
    sqlText: "SELECT SUM(total) FROM orders",
    status: "SUCCESS",
    rowCount: 1,
    executionTimeMs: 48,
    errorMessage: null,
  }],
};

describe("HistoryPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(historyApi.list).mockResolvedValue([conversation]);
    vi.mocked(historyApi.detail).mockResolvedValue([assistantMessage]);
    vi.mocked(historyApi.pinned).mockResolvedValue([]);
    vi.mocked(historyApi.search).mockResolvedValue({
      content: [], totalElements: 0, totalPages: 0, number: 0, size: 10,
    });
  });

  it("shows query execution details and uses a friendly delete dialog", async () => {
    render(<ToastProvider><HistoryPage /></ToastProvider>);

    await screen.findByText("Doanh thu tháng 8");
    await userEvent.click(screen.getByRole("button", { name: /^Doanh thu tháng 8/i }));
    expect(await screen.findByText("SQL đã tạo")).toBeInTheDocument();
    expect(screen.getByText("Chi tiết thực thi")).toBeInTheDocument();
    expect(screen.getByText("1 dòng · 48 ms")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Xóa Doanh thu tháng 8" }));
    expect(screen.getByRole("dialog", { name: "Xóa cuộc trò chuyện?" })).toBeInTheDocument();
    expect(historyApi.remove).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "Giữ lại" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("paginates search results", async () => {
    vi.mocked(historyApi.search).mockImplementation(async ({ page = 0 }) => ({
      content: [{
        messageId: page + 20,
        conversationId: 1,
        conversationTitle: `Kết quả trang ${page + 1}`,
        content: "Nội dung tìm kiếm",
        generatedSql: null,
        pinned: false,
        createdAt: "2026-09-01T08:10:00",
      }],
      totalElements: 11,
      totalPages: 2,
      number: page,
      size: 10,
    }));

    render(<ToastProvider><HistoryPage /></ToastProvider>);
    await userEvent.click(screen.getByRole("button", { name: /Tìm kiếm/i }));
    await userEvent.type(screen.getByLabelText("Từ khóa tìm kiếm lịch sử"), "doanh thu");
    const searchButtons = screen.getAllByRole("button", { name: "Tìm kiếm" });
    await userEvent.click(searchButtons[searchButtons.length - 1]);

    expect(await screen.findByText("Kết quả trang 1")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /Trang sau/i }));
    expect(await screen.findByText("Kết quả trang 2")).toBeInTheDocument();
    await waitFor(() => expect(historyApi.search).toHaveBeenLastCalledWith({
      keyword: "doanh thu", page: 1, size: 10,
    }));
  });
});
