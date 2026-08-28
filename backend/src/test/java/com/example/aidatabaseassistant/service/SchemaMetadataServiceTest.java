package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.repository.ColumnMetadataRepository;
import com.example.aidatabaseassistant.repository.TableMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaMetadataServiceTest {

    @Mock TableMetadataRepository tableRepository;
    @Mock ColumnMetadataRepository columnRepository;
    private SchemaMetadataService service;

    @BeforeEach
    void setUp() {
        service = new SchemaMetadataService(tableRepository, columnRepository);
    }

    @Test
    void ownerCanUpdateTableDescription() {
        TableMetadata table = TableMetadata.builder().id(7L).name("orders").build();
        when(tableRepository.findByIdAndSchemaConnectionUserUsernameIgnoreCase(7L, "student"))
                .thenReturn(Optional.of(table));

        service.updateTableDescription("student", 7L, "Đơn hàng đã thanh toán");

        assertThat(table.getDescription()).isEqualTo("Đơn hàng đã thanh toán");
        verify(tableRepository).save(table);
    }

    @Test
    void anotherUserCannotUpdateTableDescription() {
        when(tableRepository.findByIdAndSchemaConnectionUserUsernameIgnoreCase(9L, "student"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateTableDescription("student", 9L, "private"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(tableRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
