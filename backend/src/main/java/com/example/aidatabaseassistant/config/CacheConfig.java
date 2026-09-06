package com.example.aidatabaseassistant.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cau hinh cache tap trung cho toan bo backend.
 *
 * =====================================================================
 * VI SAO CO 2 CacheManager (localCacheManager / sharedCacheManager)
 * thay vi 1 CacheManager duy nhat?
 * =====================================================================
 *
 * 1) localCacheManager - LUON LA CAFFEINE, KHONG BAO GIO doi sang Redis
 *    (bat ke app.cache.provider la gi), TRU KHI app.cache.provider=none.
 *
 *    Dung cho cache "fullSchema" - object DatabaseSchema la JPA entity,
 *    co quan he LAZY (vi du DatabaseSchema.connection) duoc Hibernate
 *    boc bang proxy. Neu serialize entity nay sang Redis:
 *
 *      - proxy Hibernate KHONG serialize duoc bang Jackson/JDK mac dinh
 *        (can them hibernate6-module + cau hinh rieng, de loi ngam).
 *      - schema -> tables -> schema (quan he 2 chieu) de gay vong lap
 *        vo han / StackOverflow khi Jackson serialize.
 *
 *    Giu cache nay CHI trong JVM hien tai (Caffeine) la lua chon AN TOAN
 *    va DON GIAN hon nhieu so voi viec "lam cho entity serialize duoc"
 *    chi de nhet vao Redis. Doi lai: cache nay khong dung chung duoc
 *    giua nhieu instance backend (chap nhan duoc - moi instance tu tai
 *    schema 1 lan, khong anh huong tinh dung dan cua du lieu).
 *
 * 2) sharedCacheManager - Caffeine (mac dinh) hoac Redis, tuy
 *    app.cache.provider.
 *
 *    Dung cho cache la du lieu THUAN (DTO, khong dinh JPA entity/proxy)
 *    -> serialize sang Redis an toan. Vi du: thong ke admin dashboard
 *    (AdminStatsResponse), embedding cua bang (Map<String,float[]>).
 *
 *    app.cache.provider=caffeine (mac dinh) -> chay duoc ngay, KHONG can
 *    cai dat Redis, phu hop may dev / demo / 1 instance duy nhat.
 *
 *    app.cache.provider=redis -> dung khi trien khai nhieu instance
 *    backend phia sau load balancer, can cache dung chung de tat ca
 *    instance thay cung 1 gia tri (vd: dashboard admin khong bi lech
 *    so lieu giua cac instance).
 *
 * =====================================================================
 * TAT CACHE HOAN TOAN
 * =====================================================================
 *
 * app.cache.provider=none -> ca 2 CacheManager deu la NoOpCacheManager
 * (co san trong Spring): @Cacheable/@CacheEvict van chay binh thuong ve
 * mat code (khong loi NoSuchBeanDefinitionException) nhung KHONG bao gio
 * luu/tra gi ca - moi lan goi la 1 lan cache-miss thuc su, hanh vi app
 * giong het nhu khi CHUA co cache.
 *
 * File src/test/resources/application.properties da dat
 * app.cache.provider=none de bo test hien tai (700+ test) khong bi anh
 * huong boi hanh vi cache (Caffeine cache la singleton bean, KHONG bi
 * rollback theo transaction cua tung test, co the tra du lieu cu neu ID
 * bi tai su dung giua cac test dung chung Spring context).
 */
@Slf4j
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    // Ten cache dung xuyen suot project - tranh go tay chuoi string o
    // nhieu noi khac nhau (typo -> cache "am tham" khong hoat dong).
    public static final String FULL_SCHEMA_CACHE = "fullSchema";
    public static final String TABLE_EMBEDDINGS_CACHE = "tableEmbeddings";
    public static final String ADMIN_STATS_CACHE = "adminStats";

    /**
     * none   = tat cache hoan toan (dung trong test).
     * caffeine (mac dinh) = cache trong bo nho JVM, khong can ha tang gi them.
     * redis  = sharedCacheManager dung Redis de nhieu instance dung chung.
     */
    @Value("${app.cache.provider:caffeine}")
    private String cacheProvider;

    @Value("${app.cache.full-schema.ttl-minutes:60}")
    private long fullSchemaTtlMinutes;

    @Value("${app.cache.full-schema.max-size:500}")
    private long fullSchemaMaxSize;

    @Value("${app.cache.table-embeddings.ttl-minutes:30}")
    private long tableEmbeddingsTtlMinutes;

    @Value("${app.cache.admin-stats.ttl-seconds:60}")
    private long adminStatsTtlSeconds;

    @Override
    public CacheManager cacheManager() {
        return new NoOpCacheManager();
    }

    /*
     * CACHE LA TINH NANG PHU TRO - GIONG TRIET LY CUA SchemaRetrievalService
     * VOI RAG: "RAG là chức năng bổ sung... không được làm hỏng toàn bộ
     * query" (xem SchemaRetrievalService.retrieveRelevantSchema).
     *
     * Mac dinh, neu CacheManager nem exception (vi du: mat ket noi Redis
     * giua chung, sai password, timeout mang) thi Spring se NEM LOI DO RA
     * NGOAI, lam hong ca request dang xu ly - dieu nay BIEN 1 tinh nang
     * toi uu hoa (cache) thanh 1 DIEM LOI MOI cho toan he thong, dac biet
     * nguy hiem voi Redis (co the mat ket noi mang bat cu luc nao, khac
     * voi Caffeine luon nam trong JVM khong bao gio "mat ket noi").
     *
     * CacheErrorHandler nay dam bao: loi tu cache (GET/PUT/EVICT/CLEAR)
     * chi duoc GHI LOG canh bao, KHONG BAO GIO nem tiep ra ngoai. Khi loi
     * xay ra o buoc GET -> Spring coi nhu cache-miss va chay method that
     * su (tuong duong "khong co cache" cho lan goi do). Khi loi xay ra o
     * buoc PUT/EVICT -> ket qua method van tra ve binh thuong cho nguoi
     * dung, chi la lan sau co the van phai tinh lai (khong toi uu duoc,
     * nhung KHONG mat du lieu, KHONG loi API).
     */
    @Override
    @Bean
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("[Cache] Lỗi khi đọc cache '{}' key={} - coi như cache-miss: {}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("[Cache] Lỗi khi ghi cache '{}' key={} - bỏ qua, dữ liệu vẫn trả về đúng: {}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.warn("[Cache] Lỗi khi xóa cache '{}' key={} - có thể còn dữ liệu cũ đến khi hết TTL: {}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.warn("[Cache] Lỗi khi xóa toàn bộ cache '{}': {}", cache.getName(), exception.getMessage());
            }
        };
    }

    // -----------------------------------------------------------------
    // 1. localCacheManager - Caffeine, dung cho cache "fullSchema"
    // -----------------------------------------------------------------
    @Bean(name = "localCacheManager")
    public CacheManager localCacheManager() {

        if (isCacheDisabled()) {
            log.info("[Cache] app.cache.provider=none -> localCacheManager la NoOpCacheManager (cache TAT).");
            return new NoOpCacheManager();
        }

        log.info("[Cache] localCacheManager = Caffeine (TTL={} phut, maxSize={}) cho cache '{}'",
                fullSchemaTtlMinutes, fullSchemaMaxSize, FULL_SCHEMA_CACHE);

        CaffeineCacheManager manager = new CaffeineCacheManager(FULL_SCHEMA_CACHE);
        manager.setCaffeine(
                Caffeine.newBuilder()
                        // Het han sau X phut ke tu lan GHI cache gan nhat.
                        // Day la luoi an toan (safety net) - binh thuong cache
                        // se duoc xoa CHU DONG (evict) ngay khi schema thay doi
                        // (xem SchemaDiscoveryService.discoverSchema va
                        // SchemaMetadataService.updateTableDescription /
                        // updateColumnDescription). TTL chi phong truong hop
                        // co nhanh code nao do quen goi evict.
                        .expireAfterWrite(Duration.ofMinutes(fullSchemaTtlMinutes))
                        // Gioi han so schema toi da giu trong bo nho cung
                        // 1 luc - tranh OutOfMemory neu he thong co qua
                        // nhieu connection/schema dang hoat dong.
                        .maximumSize(fullSchemaMaxSize)
                        .recordStats()
        );
        return manager;
    }

    // -----------------------------------------------------------------
    // 2. sharedCacheManager - Caffeine (mac dinh) hoac Redis
    // -----------------------------------------------------------------
    @Bean(name = "sharedCacheManager")
    public CacheManager sharedCacheManager(RedisConnectionFactory redisConnectionFactory) {

        if (isCacheDisabled()) {
            log.info("[Cache] app.cache.provider=none -> sharedCacheManager la NoOpCacheManager (cache TAT).");
            return new NoOpCacheManager();
        }

        if ("redis".equalsIgnoreCase(cacheProvider)) {
            log.info("[Cache] sharedCacheManager = Redis (embeddings TTL={} phut, adminStats TTL={}s)",
                    tableEmbeddingsTtlMinutes, adminStatsTtlSeconds);
            return buildRedisCacheManager(redisConnectionFactory);
        }

        log.info("[Cache] sharedCacheManager = Caffeine (embeddings TTL={} phut, adminStats TTL={}s)",
                tableEmbeddingsTtlMinutes, adminStatsTtlSeconds);
        return buildCaffeineSharedCacheManager();
    }

    private boolean isCacheDisabled() {
        return "none".equalsIgnoreCase(cacheProvider);
    }

    private CacheManager buildCaffeineSharedCacheManager() {

        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCacheNames(List.of(TABLE_EMBEDDINGS_CACHE, ADMIN_STATS_CACHE));

        // CaffeineCacheManager mac dinh dung CHUNG 1 spec cho moi cache
        // dang ky qua setCacheNames(). Vi 2 cache nay can TTL khac nhau
        // (embeddings song lau hon nhieu so voi thong ke admin), phai
        // dang ky rieng tung cache bang registerCustomCache().
        manager.registerCustomCache(
                TABLE_EMBEDDINGS_CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofMinutes(tableEmbeddingsTtlMinutes))
                        .maximumSize(2000)
                        .recordStats()
                        .build()
        );

        manager.registerCustomCache(
                ADMIN_STATS_CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofSeconds(adminStatsTtlSeconds))
                        .maximumSize(10)
                        .recordStats()
                        .build()
        );

        return manager;
    }

    private CacheManager buildRedisCacheManager(RedisConnectionFactory connectionFactory) {

        // Key luu duoi dang chuoi de xem duoc truc tiep bang redis-cli
        // (vi du: "tableEmbeddings::42"), value serialize bang Jackson
        // (JSON) thay vi Java serialization mac dinh cua Spring - de doc,
        // de debug, va KHONG bat buoc class phai implements Serializable.
        //
        // GenericJacksonJsonRedisSerializer (Jackson 3, thay the
        // GenericJackson2JsonRedisSerializer da bi @Deprecated(forRemoval)
        // tu Spring Data Redis 4.0) KHONG tu dong bat default typing nhu
        // class cu, va enableDefaultTyping() BAT BUOC phai truyen vao 1
        // PolymorphicTypeValidator (khong nhan boolean) - day la thay doi
        // CO CHU DICH ve bao mat cua Spring Data Redis: GenericJackson2..
        // ban cu bat default typing KHONG GIOI HAN (cho phep deserialize
        // thanh BAT KY class nao co trong classpath dua vao field "@class"
        // trong JSON) - day chinh la dang lo hong deserialization kinh
        // dien (tuong tu cac CVE Jackson/Log4j nam 2019-2021). Ban moi bat
        // Spring/Anthropic phai khai bao ro RANG BUOC: chi cho phep
        // deserialize ve nhung class thuc su duoc dung lam gia tri cache
        // (whitelist), khong cho phep instantiate class bat ky.
        //
        // KHONG dung allowIfSubTypeIsArray() - CVE-2026-54513 cho thay ham
        // nay cho qua BAT KY kieu mang nao (kinh ca mang cua class KHONG
        // nam trong whitelist), pha vo muc dich cua toan bo whitelist.
        // Thay vao do, khai bao dich danh tung kieu du lieu THUC SU duoc
        // dung lam value cache trong project (AdminStatsResponse, va
        // Map<String, float[]> cho table embeddings).
        PolymorphicTypeValidator cacheValueTypeValidator = BasicPolymorphicTypeValidator.builder()
                // AdminStatsResponse va cac DTO cache khac trong tuong lai.
                .allowIfSubType("com.example.aidatabaseassistant.dto.")
                // Map/List/String can thiet de deserialize cau truc
                // Map<String, float[]> cua table embeddings.
                .allowIfSubType(java.util.Map.class)
                .allowIfSubType(java.util.List.class)
                .allowIfSubType(String.class)
                .allowIfSubType(float[].class)
                .build();

        RedisCacheConfiguration baseConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(GenericJacksonJsonRedisSerializer.builder()
                                .enableDefaultTyping(cacheValueTypeValidator)
                                .build()))
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> perCacheConfig = new HashMap<>();
        perCacheConfig.put(TABLE_EMBEDDINGS_CACHE, baseConfig.entryTtl(Duration.ofMinutes(tableEmbeddingsTtlMinutes)));
        perCacheConfig.put(ADMIN_STATS_CACHE, baseConfig.entryTtl(Duration.ofSeconds(adminStatsTtlSeconds)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(baseConfig.entryTtl(Duration.ofMinutes(10)))
                .withInitialCacheConfigurations(perCacheConfig)
                .build();
    }
}