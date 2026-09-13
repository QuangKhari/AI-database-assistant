import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useAuth } from "../context/AuthContext";
import { HomeRoute } from "./HomeRoute";

vi.mock("../context/AuthContext", () => ({ useAuth: vi.fn() }));

const mockedUseAuth = vi.mocked(useAuth);

describe("HomeRoute", () => {
  beforeEach(() => vi.clearAllMocks());

  it("shows the public landing page to unauthenticated visitors", () => {
    mockedUseAuth.mockReturnValue({ user: null, loading: false } as ReturnType<typeof useAuth>);
    render(<MemoryRouter initialEntries={["/"]}><Routes><Route path="/" element={<HomeRoute />} /></Routes></MemoryRouter>);

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Đặt câu hỏi bằng tiếng Việt");
    expect(screen.getAllByRole("link", { name: /Đăng ký miễn phí/i }).length).toBeGreaterThan(0);
    expect(screen.getAllByRole("link", { name: "Đăng nhập" })[0]).toHaveAttribute("href", "/login");
  });

  it("redirects authenticated users to their dashboard", () => {
    mockedUseAuth.mockReturnValue({ user: { username: "demo" }, loading: false } as ReturnType<typeof useAuth>);
    render(<MemoryRouter initialEntries={["/"]}><Routes><Route path="/" element={<HomeRoute />} /><Route path="/dashboard" element={<h1>Tổng quan tài khoản</h1>} /></Routes></MemoryRouter>);

    expect(screen.getByRole("heading", { name: "Tổng quan tài khoản" })).toBeInTheDocument();
  });

  it("does not flash the landing page while checking the session", () => {
    mockedUseAuth.mockReturnValue({ user: null, loading: true } as ReturnType<typeof useAuth>);
    render(<MemoryRouter><HomeRoute /></MemoryRouter>);

    expect(screen.getByText("Đang kiểm tra phiên đăng nhập…")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { level: 1 })).not.toBeInTheDocument();
  });
});
