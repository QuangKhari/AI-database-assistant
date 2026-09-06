import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { connectionApi } from "../api/connectionApi";
import { ToastProvider } from "../context/ToastContext";
import { ConnectionFormPage } from "./ConnectionFormPage";

vi.mock("../api/connectionApi", () => ({
  connectionApi: {
    get: vi.fn(),
    test: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
  },
}));

describe("ConnectionFormPage", () => {
  beforeEach(() => vi.clearAllMocks());

  function renderPage() {
    return render(
      <ToastProvider>
        <MemoryRouter>
          <ConnectionFormPage />
        </MemoryRouter>
      </ToastProvider>,
    );
  }

  it("shows required-field feedback before calling the API", async () => {
    renderPage();
    await userEvent.click(
      screen.getByRole("button", { name: "Xác minh và tạo" }),
    );

    expect(screen.getByRole("alert")).toHaveTextContent("Vui lòng nhập đầy đủ");
    expect(connectionApi.create).not.toHaveBeenCalled();
  });

  it("tests a complete read-only connection payload", async () => {
    vi.mocked(connectionApi.test).mockResolvedValue({
      successful: true,
      readOnlyVerified: true,
      code: "CONNECTION_OK",
      message: "Kết nối thành công và đã xác minh quyền chỉ đọc.",
      durationMs: 25,
      serverVersion: "8.0",
    });
    renderPage();

    await userEvent.type(
      screen.getByLabelText(/^Tên hiển thị/),
      "Sample Store",
    );
    await userEvent.type(screen.getByLabelText(/^Host/), "localhost");
    await userEvent.type(
      screen.getByLabelText(/^Tên database/),
      "sample_store",
    );
    await userEvent.type(screen.getByLabelText(/^Username DB/), "aidb_reader");

    await userEvent.type(
      screen.getByLabelText(/^Mật khẩu DB/),
      "readonly-password",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Kiểm tra kết nối" }),
    );

    await waitFor(() =>
      expect(connectionApi.test).toHaveBeenCalledWith(
        expect.objectContaining({
          host: "localhost",
          databaseName: "sample_store",
          username: "aidb_reader",
          port: 3306,
        }),
      ),
    );
    expect(screen.getByRole("alert")).toHaveTextContent("Kết nối thành công");
  });
});
