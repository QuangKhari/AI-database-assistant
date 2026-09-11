export interface UserInfo {
  id: number;
  username: string;
  email: string;
  role: "USER" | "ADMIN";
  createdAt: string;
  displayName: string | null;
}
export interface UserProfile {
  id: number;
  username: string;
  email: string;
  role: "USER" | "ADMIN";
  displayName: string | null;
}

export interface AuthResponse {
  token: string;
  username: string;
  role: "USER" | "ADMIN";
}

export interface ApiErrorBody {
  status: number;
  code: string;
  message: string;
  fieldErrors?: Record<string, string>;
  correlationId?: string;
}

export interface OperationResponse {
  message: string;
}

// dbType thực tế BE hỗ trợ (xem TargetDatabaseClient/JdbcUrlBuilder):
// mysql, postgresql qua ConnectionRequest thường; excel qua endpoint riêng
// POST /api/connections/excel (dbType trả về "excel").
export type DbType = "mysql" | "postgresql" | "excel";

export interface DatabaseConnection {
  id: number;
  name: string;
  dbType: DbType;
  host: string;
  port: number;
  databaseName: string;
  username: string;
  sslEnabled: boolean;
  active: boolean;
  lastTestedAt: string | null;
  lastTestSuccessful: boolean | null;
  createdAt: string;
  updatedAt: string;
}

export interface ConnectionPayload {
  name: string;
  dbType: DbType;
  host: string;
  port: number;
  databaseName: string;
  username: string;
  password: string;
  sslEnabled: boolean;
}

export interface ConnectionTestResult {
  successful: boolean;
  readOnlyVerified: boolean;
  code: string;
  message: string;
  durationMs: number;
  serverVersion: string | null;
}

// Khớp AdminStatsResponse.java thật (BE không có activeUsers/lockedUsers/
// activeConnections; thay bằng totalConnections/totalConversations/totalQueries).
export interface AdminStats {
  totalUsers: number;
  totalConnections: number;
  totalConversations: number;
  totalQueries: number;
}

// Khớp AdminUserResponse.java thật - KHÔNG có displayName/enabled/updatedAt
// (những field này chưa tồn tại ở BE, không được tự bịa ra).
export interface AdminUser {
  id: number;
  username: string;
  email: string;
  role: "USER" | "ADMIN";
  createdAt: string;
  connectionCount: number;
  locked: boolean;
}

// Khớp AdminConnectionResponse.java thật.
export interface AdminConnection {
  id: number;
  name: string;
  dbType: string;
  host: string;
  databaseName: string;
  ownerUsername: string;
  createdAt: string;
}

// BE hiện trả List<AdminUserResponse> (không phân trang) từ GET /admin/users
// và GET /admin/users/search?keyword=. FE tự phân trang phía client.
export interface AdminUserPage {
  content: AdminUser[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface SchemaColumn {
  id: number;
  name: string;
  dataType: string;
  nullable: boolean;
  primaryKey: boolean;
  foreignKey: boolean;
  referencedTable: string | null;
  referencedColumn: string | null;
  description: string | null;
}

export interface SchemaTable {
  id: number;
  name: string;
  description: string | null;
  columns: SchemaColumn[];
}

export interface DatabaseSchema {
  id: number;
  connectionId: number;
  databaseName: string;
  lastSyncedAt: string;
  tables: SchemaTable[];
}

export interface Conversation {
  id: number;
  title: string;
  connectionId: number;
  createdAt: string;
  updatedAt: string;
}

export interface QueryLog {
  attemptNumber: number;
  sqlText: string;
  status: string;
  rowCount: number | null;
  executionTimeMs: number | null;
  errorMessage: string | null;
}

export interface ChatMessage {
  id: number;
  role: "user" | "assistant";
  content: string;
  generatedSql: string | null;
  createdAt: string;
  queryLogs: QueryLog[];
  pinned: boolean;
  /**
   * QueryResponse được backend persist để khôi phục kết quả khi
   * mở lại conversation hoặc reload trang.
   */
  queryResult: QueryResponse | null;
}

export interface ChatPreviewResult {
  generatedSql: string;
  valid: boolean;
  errorMessage: string | null;
}

export interface QueryResult {
  columns: string[];
  rows: Record<string, unknown>[];
  executionTimeMs: number;
  rowCount: number;
  error: string | null;
}

export type ChartType = "BAR" | "LINE" | "PIE" | "DONUT" | "TABLE";

export interface ChartSeries {
  name: string;
  values: number[];
}

// Khớp ChartSuggestionResponse.java
export interface ChartSuggestion {
  chartType: ChartType;
  alternativeChartTypes: ChartType[];
  xAxisColumn: string | null;
  xAxisLabels: string[];
  series: ChartSeries[];
  reason: string;
}

export interface ChartSuggestionRequest {
  columns: string[];
  rows: Record<string, unknown>[];
  connectionId?: number;
}

export interface Anomaly {
  label: string;
  value: number;
  direction: string;
}

// Khớp DataInsightResponse.java
export interface DataInsight {
  numericColumn: string;
  dimensionColumn: string;
  highestLabel: string;
  highestValue: number;
  lowestLabel: string;
  lowestValue: number;
  growthPercent: number | null;
  trend: "UP" | "DOWN" | "FLAT" | null;
  periodStartLabel: string | null;
  periodEndLabel: string | null;
  topShareLabel: string | null;
  topSharePercent: number | null;
  anomalies: Anomaly[];
  summary: string;
}

export interface QueryResponse {
  conversationId: number;
  messageId: number;
  generatedSql: string;
  result: QueryResult;
  summary: string | null;
  attemptCount: number;
  chartSuggestion: ChartSuggestion | null;
  dataInsight: DataInsight | null;
}

// Khớp SuggestedQuestionsResponse.java
export interface SuggestedQuestionsResult {
  questions: string[];
  source: "ai" | "template" | "cache";
}

// Khớp MessageSearchResultResponse.java (dùng cho History search)
export interface HistorySearchResult {
  messageId: number;
  conversationId: number;
  conversationTitle: string;
  content: string;
  generatedSql: string | null;
  pinned: boolean;
  createdAt: string;
}

export interface PageResult<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number; // trang hiện tại (0-based, theo Spring Page)
  size: number;
}

export interface ExplainSqlRequest {
  sql: string;
  databaseConnectionId?: number;
}

// Khớp SqlExplanationStep.java: mỗi bước là 1 mệnh đề SQL (clause) kèm
// giải thích tiếng Việt (explanation) - KHÔNG phải chuỗi đơn giản.
export interface SqlExplanationStep {
  clause: string;
  explanation: string;
}

export interface ExplainSqlResponse {
  sql: string;
  summary: string;
  steps: SqlExplanationStep[];
}

export interface OptimizeSqlRequest {
  sql: string;
  databaseConnectionId: number;
}

// Khớp ExplainRowDto.java - 1 dòng trong kết quả EXPLAIN của MySQL.
// Các field có thể null tùy loại truy vấn (vd không dùng index -> key=null).
export interface ExplainRow {
  id: number | null;
  selectType: string | null;
  table: string | null;
  type: string | null;
  possibleKeys: string | null;
  key: string | null;
  rows: number | null;
  extra: string | null;
}

// Khớp OptimizationIssueDto.java. severity: "HIGH" | "MEDIUM" | "LOW".
export interface OptimizationIssue {
  severity: "HIGH" | "MEDIUM" | "LOW" | string;
  table: string;
  description: string;
}

// Khớp IndexSuggestionDto.java.
export interface IndexSuggestion {
  table: string;
  columns: string[];
  reason: string;
  createIndexSql: string;
}

// Khớp OptimizeSqlResponse.java thật - explainPlan/issues/suggestions đều
// là mảng object, KHÔNG phải string/string[] như bản cũ (bản cũ sẽ lỗi khi
// render vì BE không bao giờ trả về đúng shape đó).
export interface OptimizeSqlResponse {
  sql: string;
  explainPlan: ExplainRow[];
  issues: OptimizationIssue[];
  suggestions: IndexSuggestion[];
  aiSummary: string;
}

export interface QueryStreamStatusEvent {
  type: "STATUS";
  message: string;
}

export interface QueryStreamResultEvent {
  type: "result";
  data: QueryResponse;
}

export interface QueryStreamSummaryEvent {
  type: "summary";
  summary: string;
}

export interface QueryStreamErrorEvent {
  type: "error";
  message: string;
}

export type QueryStreamEvent =
  | QueryStreamStatusEvent
  | QueryStreamResultEvent
  | QueryStreamSummaryEvent
  | QueryStreamErrorEvent;

export interface DataInsightRequest {
  columns: string[];
  rows: Record<string, unknown>[];
  connectionId?: number;
}

export interface BenchmarkQuestion {
  id: number;
  language: "VI" | "EN";
  questionText: string;
  expectedSql: string;
  connectionId: number;
}

export interface BenchmarkRunDetail {
  questionText: string;
  generatedSql: string | null;
  expectedSql: string;
  correct: boolean;
  latencyMs: number;
  errorMessage: string | null;
}

export interface BenchmarkRunResponse {
  totalQuestions: number;
  correctCount: number;
  accuracy: number;
  details: BenchmarkRunDetail[];
}
