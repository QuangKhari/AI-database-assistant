package com.example.aidatabaseassistant.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "table_embedding",
        uniqueConstraints = @UniqueConstraint(
                // 1 bảng chỉ có 1 embedding hiện hành trong 1 schema
                columnNames = {"schema_id", "table_name"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TableEmbedding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Trỏ tới DatabaseSchema (KHÔNG phải TableMetadata) vì schema.id ổn
    // định qua các lần đồng bộ, còn table.id thì bị xoá/tạo lại mỗi lần
    // discoverSchema() chạy (do orphanRemoval=true trên DatabaseSchema.tables).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schema_id", nullable = false)
    private DatabaseSchema schema;

    // Dùng tên bảng làm khoá tra cứu thay vì table_id, vì tên bảng thực tế
    // (trong MySQL thật của người dùng) không đổi giữa các lần re-sync,
    // ngay cả khi row TableMetadata bị xoá/tạo lại.
    @Column(name = "table_name", nullable = false, length = 100)
    private String tableName;

    // Vector lưu dạng JSON text (mảng float). Không cần kiểu VECTOR native
    // vì cosine similarity được tính ở tầng ứng dụng (Java), không phải
    // trong DB — MySQL bản đang dùng cũng không có VECTOR type ổn định.
    @Lob
    @Column(name = "vector_json", nullable = false, columnDefinition = "TEXT")
    private String vectorJson;

    // Hash nội dung dùng để embed (tên bảng + cột + mô tả + PK/FK).
    // Nếu hash không đổi giữa 2 lần discover -> bỏ qua, không gọi lại
    // Gemini Embedding API (tiết kiệm quota + thời gian discover).
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}