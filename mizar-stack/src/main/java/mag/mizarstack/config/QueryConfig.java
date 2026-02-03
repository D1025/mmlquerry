package mag.mizarstack.config;

import mag.mizarstack.query.eval.QueryEvaluationService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for query evaluation services.
 */
@Configuration
public class QueryConfig {

    @Bean
    public QueryEvaluationService queryEvaluationService(JdbcClient jdbcClient) {
        return new QueryEvaluationService(jdbcClient);
    }
}

