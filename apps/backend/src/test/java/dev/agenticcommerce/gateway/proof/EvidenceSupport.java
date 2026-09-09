package dev.agenticcommerce.gateway.proof;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public final class EvidenceSupport {
    private EvidenceSupport() {}

    public static ObjectNode envelope(ObjectMapper mapper,String schemaVersion,int sampleSize,String command,
            ObjectNode summary,JsonNode details,List<String> limitations,String whatThisDoesNotProve){
        ObjectNode root=mapper.createObjectNode();root.put("schemaVersion",schemaVersion);
        ObjectNode provenance=root.putObject("provenance");provenance.put("commitSha",git("rev-parse","HEAD"));
        provenance.put("workingTreeDirty",!git("status","--porcelain").isBlank());
        provenance.put("generatedAtUtc",Instant.now().toString());ObjectNode runtime=provenance.putObject("runtime");
        runtime.put("java",System.getProperty("java.runtime.version"));runtime.put("node",nodeVersion());
        provenance.put("operatingSystem",System.getProperty("os.name")+" "+System.getProperty("os.version")+" "+System.getProperty("os.arch"));
        provenance.put("hostLabel",host());provenance.put("sampleSize",sampleSize);provenance.put("command",command);
        root.set("summary",summary);root.set("details",details);root.set("limitations",mapper.valueToTree(limitations));
        root.put("whatThisDoesNotProve",whatThisDoesNotProve);return root;
    }

    public static void write(ObjectMapper mapper,String jsonName,String markdownName,ObjectNode artifact,String markdown){
        try{Path directory=repositoryRoot().resolve("proof/results");Files.createDirectories(directory);
            Files.writeString(directory.resolve(jsonName),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(artifact)+System.lineSeparator(),StandardCharsets.UTF_8);
            Files.writeString(directory.resolve(markdownName),markdownWithProvenance(markdown,artifact),StandardCharsets.UTF_8);
        }catch(IOException failure){throw new IllegalStateException("Cannot write evidence artifact",failure);}
    }

    private static String markdownWithProvenance(String markdown,ObjectNode artifact){
        JsonNode provenance=artifact.path("provenance"),runtime=provenance.path("runtime");
        StringBuilder result=new StringBuilder(markdown.stripTrailing()).append("\n\n## Provenance\n\n")
                .append("- Schema version: `").append(artifact.path("schemaVersion").asText()).append("`\n")
                .append("- Commit SHA: `").append(provenance.path("commitSha").asText()).append("`\n")
                .append("- Working tree dirty: `").append(provenance.path("workingTreeDirty").asBoolean()).append("`\n")
                .append("- Generated UTC: `").append(provenance.path("generatedAtUtc").asText()).append("`\n")
                .append("- Runtime: Java `").append(runtime.path("java").asText()).append("`; Node `").append(runtime.path("node").asText()).append("`\n")
                .append("- OS / host: `").append(provenance.path("operatingSystem").asText()).append("` / `").append(provenance.path("hostLabel").asText()).append("`\n")
                .append("- Sample size: ").append(provenance.path("sampleSize").asInt()).append("\n")
                .append("- Exact command: `").append(provenance.path("command").asText()).append("`\n")
                .append("- Summary: **").append(artifact.path("summary").path("status").asText()).append("**\n\n")
                .append("## Limitations\n\n");
        artifact.path("limitations").forEach(value->result.append("- ").append(value.asText()).append("\n"));
        return result.append("\n## What this does not prove\n\n")
                .append(artifact.path("whatThisDoesNotProve").asText()).append("\n").toString();
    }

    public static Path repositoryRoot() {
        return repositoryRoot(Path.of(System.getProperty("user.dir")));
    }

    static Path repositoryRoot(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null && !isAmanaRepositoryRoot(current)) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Repository root not found");
        }
        return current;
    }

    private static boolean isAmanaRepositoryRoot(Path candidate) {
        return Files.isRegularFile(candidate.resolve("package.json"))
                && Files.isRegularFile(candidate.resolve("apps/backend/pom.xml"))
                && Files.isDirectory(candidate.resolve("apps/web"))
                && Files.isDirectory(candidate.resolve("proof"));
    }

    private static String git(String...args){try{List<String> command=new java.util.ArrayList<>();command.add("git");command.addAll(List.of(args));
        Process process=new ProcessBuilder(command).directory(repositoryRoot().toFile()).redirectErrorStream(true).start();
        String value=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip();
        return process.waitFor()==0?value:"UNAVAILABLE";}catch(Exception failure){return "UNAVAILABLE";}}
    private static String nodeVersion(){try{Process process=new ProcessBuilder("node","--version").redirectErrorStream(true).start();
        String value=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip();return process.waitFor()==0?value:"UNAVAILABLE";
        }catch(Exception failure){return "UNAVAILABLE";}}
    private static String host(){String runner=System.getenv("RUNNER_NAME");if(runner!=null&&!runner.isBlank())return runner;
        try{return InetAddress.getLocalHost().getHostName();}catch(Exception failure){return "UNKNOWN_HOST";}}

    public static double percentile(List<Long> sorted,double quantile){if(sorted.isEmpty())return Double.NaN;
        int index=(int)Math.ceil(quantile*sorted.size())-1;return sorted.get(Math.max(0,Math.min(index,sorted.size()-1)))/1_000_000.0;}
    public static String status(boolean pass){return pass?"PASS":"FAIL";}
}
