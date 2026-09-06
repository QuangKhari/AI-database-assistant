import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { adminApi } from "../api/adminApi";
import { ToastProvider } from "../context/ToastContext";
import { AdminPage } from "./AdminPage";

vi.mock("../api/adminApi", () => ({
  adminApi: {
    stats: vi.fn(),
    users: vi.fn(),
    lock: vi.fn(),
    unlock: vi.fn(),
    connections: vi.fn(),
    deleteConnection: vi.fn(),
  },
}));

describe("AdminPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(adminApi.stats).mockResolvedValue({
      totalUsers: 1,
      totalConnections: 2,
      totalConversations: 5,
      totalQueries: 20,
    });
    vi.mocked(adminApi.users).mockResolvedValue({
      content: [
        {
          id: 8,
          username: "student",
          email: "student@example.com",
          role: "USER",
          locked: false,
          connectionCount: 2,
          createdAt: "2026-08-25T00:00:00",
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });
    vi.mocked(adminApi.lock).mockResolvedValue({
      id: 8,
      username: "student",
      email: "student@example.com",
      role: "USER",
      locked: true,
      connectionCount: 2,
      createdAt: "2026-08-25T00:00:00",
    });
    vi.mocked(adminApi.connections).mockResolvedValue([
      {
        id: 3,
        name: "Demo MySQL",
        dbType: "mysql",
        host: "localhost",
        databaseName: "demo_db",
        ownerUsername: "student",
        createdAt: "2026-08-25T00:00:00",
      },
    ]);
    vi.mocked(adminApi.deleteConnection).mockResolvedValue(undefined);
    vi.spyOn(window, "confirm").mockReturnValue(true);
  });

  it("renders stats and lets an admin lock a regular user", async () => {
    render(
      <ToastProvider>
        <AdminPage />
      </ToastProvider>,
    );

    // "student" xuất hiện ở CẢ 2 bảng (username trong bảng Users, và
    // ownerUsername trong bảng Connections) - dùng findAllByText thay vì
    // findByText (chỉ chấp nhận đúng 1 phần tử khớp) để tránh lỗi
    // "Found multiple elements with the text: student".
    expect((await screen.findAllByText("student")).length).toBeGreaterThan(0);
    expect(screen.getAllByText("2").length).toBeGreaterThan(0);
    await userEvent.click(screen.getByRole("button", { name: "Khóa" }));

    await waitFor(() => expect(adminApi.lock).toHaveBeenCalledWith(8));
  });

  it("lets an admin delete a connection", async () => {
    render(
      <ToastProvider>
        <AdminPage />
      </ToastProvider>,
    );

    expect(await screen.findByText("Demo MySQL")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Xóa" }));

    await waitFor(() =>
      expect(adminApi.deleteConnection).toHaveBeenCalledWith(3),
    );
  });
});
