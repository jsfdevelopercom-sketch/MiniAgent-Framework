import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class scratch2 {
    public static void main(String[] args) throws Exception {
        String json = "{\n  \"candidates\": [\n    {\n      \"content\": {\n        \"parts\": [\n          {\n            \"text\": \"{\\\"thoughtSignature\\\": \\\"abc\\\"}\"\n          }\n        ]\n      }\n    }\n  ]\n}";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);
        JsonNode sigNode = root.findValue("thoughtSignature");
        System.out.println("sigNode = " + sigNode);
    }
}
