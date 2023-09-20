package io.diagrid.dapr;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.Transferable;
import org.yaml.snakeyaml.Yaml;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class DaprContainer extends GenericContainer<DaprContainer> {

    public static class Component {
        String name;

        String type;

        Map<String, String> metadata;

        public Component(String name, String type, Map<String, String> metadata) {
            this.name = name;
            this.type = type;
            this.metadata = metadata;
        }
    }

    private final Set<Component> components = new HashSet<>();

    private GenericContainer<?> redis = null;

    public DaprContainer(String dockerImageName, String appName) {
        super(dockerImageName);

        withCommand(
                "./daprd",
                "-app-id", appName,
                "--dapr-listen-addresses=0.0.0.0",
                "-components-path", "/components"
        );
        withExposedPorts(50001);
    }

    public DaprContainer withComponent(Component component) {
        components.add(component);
        return this;
    }

    public DaprContainer withComponent(String name, String type, Map<String, String> metadata) {
        components.add(new Component(name, type, metadata));
        return this;
    }

    @Override
    protected void doStart() {
        if (components.stream().noneMatch(it -> "statestore".equals(it.name))) {
            redis = new GenericContainer<>("redis:6-alpine")
                    .withCreateContainerCmdModifier(cmd -> {
                        cmd.getHostConfig().withNetworkMode("container:" + getContainerId());
                    });

            //components.add(new Component("statestore", "state.redis", Map.of()));

            components.add(new Component("statestore", "state.in-memory", Map.of()));
            //components.add(new Component("pubsub", "pubsub.in-memory", Map.of()));
        }

        super.doStart();

        if (redis != null) {
            redis.start();
        }
    }

    @Override
    public void stop() {
        super.stop();
        if (redis != null) {
            redis.stop();
        }
    }

    @Override
    protected void configure() {
        super.configure();

        for (Component component : components) {
            var yaml = new Yaml();

            String componentYaml = yaml.dump(
                    Map.ofEntries(
                            Map.entry("apiVersion", "dapr.io/v1alpha1"),
                            Map.entry("kind", "Component"),
                            Map.entry(
                                    "metadata", Map.ofEntries(
                                            Map.entry("name", component.name)
                                    )
                            ),
                            Map.entry("spec", Map.ofEntries(
                                    Map.entry("type", component.type),
                                    Map.entry("version", "v1"),
                                    Map.entry(
                                            "metadata",
                                            component.metadata.entrySet()
                                                    .stream()
                                                    .map(it -> Map.of("name", it.getKey(), "value", it.getValue()))
                                                    .toList()
                                    )
                            ))
                    )
            );

            withCopyToContainer(
                    Transferable.of(componentYaml), "/components/" + component.name + ".yaml"
            );
        }

    }
}