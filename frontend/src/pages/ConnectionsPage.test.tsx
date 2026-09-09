import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { connectionApi } from "../api/connectionApi";
import { ToastProvider } from "../context/ToastContext";
import { ConnectionsPage } from "./ConnectionsPage";

vi.mock("../api/connectionApi", () => ({
  connectionApi: {
    list: vi.fn(),
    reconnect: vi.fn(),
    disconnect: vi.fn(),
    uploadExcel: vi.fn(),
  },
}));

describe("ConnectionsPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(connectionApi.list).mockResolvedValue([]);
  });

  it("shows a Vietnamese Excel picker and the selected filename", async () => {
    render(
      <MemoryRouter>
        <ToastProvider>
          <ConnectionsPage />
        </ToastProvider>
      </MemoryRouter>,
    );

    const input = screen.getByLabelText("Chọn file Excel");
    expect(screen.getByText("Chọn file")).toBeInTheDocument();
    expect(screen.getByText("Chưa chọn file .xlsx")).toBeInTheDocument();

    const file = new File(["demo"], "doanh-so-2026.xlsx", {
      type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    });
    await userEvent.upload(input, file);

    expect(screen.getByText("doanh-so-2026.xlsx")).toBeInTheDocument();
  });
});
