package it.gov.pagopa.node.cfgsync.service;

import feign.Feign;
import feign.FeignException;
import feign.Response;
import it.gov.pagopa.node.cfgsync.client.ApiConfigCacheClient;
import it.gov.pagopa.node.cfgsync.exception.AppError;
import it.gov.pagopa.node.cfgsync.exception.AppException;
import it.gov.pagopa.node.cfgsync.model.TargetRefreshEnum;
import it.gov.pagopa.node.cfgsync.repository.model.ConfigCache;
import it.gov.pagopa.node.cfgsync.repository.nexioracle.CacheNodoNexiPRepository;
import it.gov.pagopa.node.cfgsync.repository.pagopa.CacheNodoPagoPAPRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
@Slf4j
public class ApiConfigCacheService extends CommonCacheService implements CacheService {

    private static final String HEADER_CACHE_ID = "X-CACHE-ID";
    private static final String HEADER_CACHE_TIMESTAMP = "X-CACHE-TIMESTAMP";
    private static final String HEADER_CACHE_VERSION = "X-CACHE-VERSION";

    @Value("${service.api-config-cache.enabled}")
    private boolean enabled;
    @Value("${service.api-config-cache.subscriptionKey}")
    private String subscriptionKey;
    @Value("${nodo-dei-pagamenti-cache-rx-connection-string}")
    private String nodoCacheRxConnectionString;
    @Value("${nodo-dei-pagamenti-cache-rx-name}")
    private String nodoCacheRxName;
    @Value("${nodo-dei-pagamenti-cache-sa-connection-string}")
    private String nodoCacheSaConnectionString;
    @Value("${nodo-dei-pagamenti-cache-sa-name}")
    private String nodoCacheSaContainerName;
    @Value("${nodo-dei-pagamenti-cache-consumer-group}")
    private String nodoCacheConsumerGroup;

    private final ApiConfigCacheClient apiConfigCacheClient;

    @Autowired
    private CacheNodoPagoPAPRepository cacheNodoPagoPAPRepository;
    @Autowired
    private CacheNodoNexiPRepository cacheNodoNexiPRepository;
//    @Autowired
//    private TransactionTemplate transactionTemplate;
//
//    transactionTemplate.execute(new TransactionCallbackWithoutResult() {
//        @Override
//        public void doInTransactionWithoutResult(TransactionStatus transactionStatus) {
//            cacheNodoPagoPAPRepository.save(configCache);
//            cacheNodoNexiPRepository.save(configCache);
//        }
//    });

    private final TransactionTemplate transactionTemplate;

    public ApiConfigCacheService(@Value("${service.api-config-cache.host}") String apiConfigCacheUrl, PlatformTransactionManager transactionManager) {
        apiConfigCacheClient = Feign.builder().target(ApiConfigCacheClient.class, apiConfigCacheUrl);
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public TargetRefreshEnum getType() {
        return TargetRefreshEnum.config;
    }

    @Override
    @Transactional
    public void sync() {
        try {
            if( !enabled ) {
                throw new AppException(AppError.SERVICE_DISABLED, getType());
            }
            log.debug("SyncService api-config-cache get cache");
            Response response = apiConfigCacheClient.getCache(subscriptionKey);
            int httpResponseCode = response.status();
            if (httpResponseCode != HttpStatus.OK.value()) {
                log.error("SyncService api-config-cache get cache error - result: httpStatusCode[{}]", httpResponseCode);
                throw new AppException(AppError.INTERNAL_SERVER_ERROR);
            }
            log.info("SyncService api-config-cache get cache successful");

            Map<String, Collection<String>> headers = response.headers();
            if( headers.isEmpty() ) {
                log.error("SyncService api-config-cache get cache error - empty header");
                throw new AppException(AppError.INTERNAL_SERVER_ERROR);
            }
            String cacheId = (String) getHeaderParameter(getType(), headers, HEADER_CACHE_ID);
            String cacheTimestamp = (String) getHeaderParameter(getType(), headers, HEADER_CACHE_TIMESTAMP);
            String cacheVersion = (String) getHeaderParameter(getType(), headers, HEADER_CACHE_VERSION);

            ConfigCache configCache = composeCache(cacheId, ZonedDateTime.parse(cacheTimestamp).toLocalDateTime(), cacheVersion, response.body().asInputStream().readAllBytes());

            this.transactionTemplate.execute(new TransactionCallbackWithoutResult() {
                public void doInTransactionWithoutResult(TransactionStatus status) {
                    try {
                        cacheNodoPagoPAPRepository.save(configCache);
                        cacheNodoNexiPRepository.save(configCache);
                    } catch(NoSuchElementException ex) {
                        status.setRollbackOnly();
                    }
                }
            });
        } catch (FeignException.GatewayTimeout e) {
            log.error("SyncService api-config-cache get cache error: Gateway timeout", e);
            throw new AppException(AppError.INTERNAL_SERVER_ERROR);
        } catch (IOException e) {
            log.error("SyncService api-config-cache get cache error", e);
            throw new AppException(AppError.INTERNAL_SERVER_ERROR);
        }
    }
}
