package ninja.samryecroft.returnhome.tracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ReturnHomeTrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReturnHomeTrackerApplication.class, args);
    }

}
