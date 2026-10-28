import com.intel.cosbench.config.XmlConfig;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PackagingProbe {
    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 21) {
            throw new IllegalStateException("The packaged module must run on Java 21");
        }
        try (DataInputStream input = new DataInputStream(XmlConfig.class.getResourceAsStream("XmlConfig.class"))) {
            if (input.readInt() != 0xcafebabe) {
                throw new IllegalStateException("Invalid class file");
            }
            input.readUnsignedShort();
            if (input.readUnsignedShort() != 65) {
                throw new IllegalStateException("The packaged module must target Java 21 bytecode");
            }
        }
        for (String name : List.of("auth", "storage", "operation", "work", "mission", "stage", "workflow", "workload")) {
            try (InputStream input = XmlConfig.class.getResourceAsStream("castor/" + name + "-mapping.xml")) {
                if (input == null) {
                    throw new IllegalStateException("Missing packaged mapping: " + name);
                }
                XmlConfig config = new XmlConfig(input);
                String xml = new String(config.getContent().readAllBytes(), StandardCharsets.UTF_8);
                if (!xml.contains("<mapping>") || !xml.contains("</mapping>")) {
                    throw new IllegalStateException("Invalid packaged mapping: " + name);
                }
            }
        }
        System.out.println("runtime_java=21; artifact_class_major=65; packaged_mapping_resources=8; status=passed");
    }
}
