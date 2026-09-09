import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { connectionApi } from "../api/connectionApi";
import { schemaApi } from "../api/schemaApi";
import { ToastProvider } from "../context/ToastContext";
import { SchemaExplorerPage } from "./SchemaExplorerPage";

vi.mock("../api/connectionApi", () => ({ connectionApi: { list: vi.fn() } }));
vi.mock("../api/schemaApi", () => ({
  schemaApi: { get: vi.fn(), sync: vi.fn() },
}));

const schema = {
  id: 1,
  connectionId: 2,
  databaseName: "shop",
  lastSyncedAt: "2026-08-27T10:00:00",
  tables: [
    {
      id: 3,
      name: "orders",
      description: null,
      columns: [
        {
          id: 4,
          name: "customer_id",
          dataType: "BIGINT",
          nullable: false,
          primaryKey: false,
          foreignKey: true,
          referencedTable: "customers",
          referencedColumn: "id",
          description: null,
        },
      ],
    },
  ],
};

describe("SchemaExplorerPage", () => {
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
        active: true,
        lastTestedAt: null,
        lastTestSuccessful: true,
        createdAt: "",
        updatedAt: "",
      },
    ]);
    vi.mocked(schemaApi.get).mockResolvedValue(schema);
    vi.mocked(schemaApi.sync).mockResolvedValue(schema);
  });

  it("shows metadata and allows the owner to sync it", async () => {
    render(
      <ToastProvider>
        <SchemaExplorerPage />
      </ToastProvider>,
    );

    expect(await screen.findAllByText("orders")).toHaveLength(2);
    expect(screen.getByText("customer_id")).toBeInTheDocument();
    expect(screen.getAllByText("FK")).toHaveLength(2);
    await userEvent.click(
      screen.getByRole("button", { name: /Đồng bộ schema/ }),
    );

    await waitFor(() => expect(schemaApi.sync).toHaveBeenCalledWith(2));
  });
});
