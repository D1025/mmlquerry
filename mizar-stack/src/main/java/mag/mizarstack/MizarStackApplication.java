package mag.mizarstack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MizarStackApplication {
    public static void main(String[] args) {
        SpringApplication.run(MizarStackApplication.class, args);
    }

}
