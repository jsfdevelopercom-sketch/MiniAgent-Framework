import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class scratch {
    public static void main(String[] args) throws Exception {
        String json = "{\n  \"candidates\": [\n    {\n      \"content\": {\n        \"parts\": [\n          {\n            \"text\": \"{\\\"thoughtSignature\\\": \\\"abc\\\"}\"\n          }\n        ]\n      }\n    }\n  ]\n}";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);
        JsonNode sigNode = root.findValue("thoughtSignature");
        System.out.println("sigNode from string = " + sigNode);
    }
}
