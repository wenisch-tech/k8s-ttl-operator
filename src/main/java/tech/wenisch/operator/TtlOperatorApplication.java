package tech.wenisch.operator;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Main entry point for the k8s-ttl-operator.
 *
 * <p>This operator watches Kubernetes resources annotated with {@code wenisch.tech/ttl}
 * and automatically deletes them when the specified UTC timestamp is reached.
 */
@QuarkusMain
public class TtlOperatorApplication implements QuarkusApplication {

    private static final Logger LOG = Logger.getLogger(TtlOperatorApplication.class);

    @Inject
    io.javaoperatorsdk.operator.Operator operator;

    public static void main(String... args) {
        Quarkus.run(TtlOperatorApplication.class, args);
    }

    @Override
    public int run(String... args) throws Exception {
        LOG.info("Starting k8s-ttl-operator...");
        operator.start();
        Quarkus.waitForExit();
        return 0;
    }
}
