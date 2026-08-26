package com.dushnyj.tgproxy;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchChangedHostKeyException;
import com.jcraft.jsch.Session;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

final class JschVpsSshClient implements VpsSshClient {
    private static final int MAX_COMMAND_OUTPUT_BYTES = 2 * 1024 * 1024;
    private static final Object HOST_KEY_LOCK = new Object();

    private final File knownHostsFile;
    private final TofuHostKeyRepository.Approver hostKeyApprover;

    JschVpsSshClient(File knownHostsFile) {
        this(knownHostsFile, null);
    }

    JschVpsSshClient(File knownHostsFile,
                     TofuHostKeyRepository.Approver hostKeyApprover) {
        if (knownHostsFile == null) throw new IllegalArgumentException("knownHostsFile == null");
        this.knownHostsFile = knownHostsFile;
        this.hostKeyApprover = hostKeyApprover;
    }

    @Override
    public String execute(VpsSshCredentials credentials, VpsSetupProgress.Stage stage,
                          String command, String stdin, int timeoutMs) throws Exception {
        if (credentials == null || !credentials.isValid()) {
            throw new VpsSetupException("invalid SSH credentials");
        }
        Session session = null;
        ChannelExec channel = null;
        try {
            synchronized (HOST_KEY_LOCK) {
                JSch jsch = createJsch();
                session = jsch.getSession(credentials.user(), credentials.host(), credentials.port());
                if (!credentials.password().isEmpty()) session.setPassword(credentials.password());
                Properties config = new Properties();
                config.put("StrictHostKeyChecking", "yes");
                config.put("PreferredAuthentications", "password,keyboard-interactive,publickey");
                session.setConfig(config);
                session.connect(connectTimeout(timeoutMs));
            }
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command == null || command.trim().isEmpty() ? "sh -s" : command);
            if (stdin != null) {
                channel.setInputStream(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
            }
            BoundedOutputStream out = new BoundedOutputStream(MAX_COMMAND_OUTPUT_BYTES);
            BoundedOutputStream err = new BoundedOutputStream(MAX_COMMAND_OUTPUT_BYTES);
            channel.setOutputStream(out);
            channel.setErrStream(err);
            channel.connect(connectTimeout(timeoutMs));
            long deadline = System.currentTimeMillis() + Math.max(1_000, timeoutMs);
            while (!channel.isClosed()) {
                if (System.currentTimeMillis() > deadline) {
                    throw new VpsSetupException("SSH command timeout");
                }
                Thread.sleep(100);
            }
            if (out.truncated() || err.truncated()) {
                throw new VpsSetupException("SSH command output exceeded 2 MiB");
            }
            String stdout = out.utf8();
            String stderr = err.utf8();
            int exit = channel.getExitStatus();
            if (exit != 0) {
                throw new VpsSetupException(stderr.isEmpty()
                        ? "SSH command failed: " + exit
                        : stderr.trim());
            }
            return stdout;
        } catch (JSchChangedHostKeyException e) {
            throw new VpsSetupException("SSH host key changed for "
                    + credentials.host() + ":" + credentials.port()
                    + ". Verify the VPS, then reset its saved SSH key in the setup dialog.", e);
        } finally {
            if (channel != null) channel.disconnect();
            if (session != null) session.disconnect();
        }
    }

    boolean forgetHost(String host, int port) throws Exception {
        String normalizedHost = host == null ? "" : host.trim();
        if (normalizedHost.isEmpty()) throw new IllegalArgumentException("host is empty");
        int normalizedPort = port > 0 && port <= 65535 ? port : 22;
        String repositoryHost = repositoryHost(normalizedHost, normalizedPort);
        synchronized (HOST_KEY_LOCK) {
            JSch jsch = createJsch();
            HostKeyRepository repository = jsch.getHostKeyRepository();
            if (repository.getHostKey(repositoryHost, null).length == 0) return false;
            repository.remove(repositoryHost, null);
            return repository.getHostKey(repositoryHost, null).length == 0;
        }
    }

    private JSch createJsch() throws Exception {
        ensureKnownHostsFile();
        JSch jsch = new JSch();
        jsch.setKnownHosts(knownHostsFile.getAbsolutePath());
        jsch.setHostKeyRepository(new TofuHostKeyRepository(
                jsch.getHostKeyRepository(), hostKeyApprover));
        return jsch;
    }

    private void ensureKnownHostsFile() throws IOException {
        File parent = knownHostsFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create SSH known-hosts directory");
        }
        if (!knownHostsFile.exists()) {
            try (FileOutputStream ignored = new FileOutputStream(knownHostsFile, true)) {
                // Create the app-private store before the first explicitly approved host key is pinned.
            }
        }
        if (!knownHostsFile.isFile() || !knownHostsFile.canRead() || !knownHostsFile.canWrite()) {
            throw new IOException("SSH known-hosts file is not accessible");
        }
    }

    private static int connectTimeout(int timeoutMs) {
        return Math.max(1_000, Math.min(timeoutMs, 20_000));
    }

    static String repositoryHost(String host, int port) {
        return port == 22 ? host : "[" + host + "]:" + port;
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private final int limit;
        private boolean truncated;

        BoundedOutputStream(int limit) {
            this.limit = Math.max(1, limit);
        }

        @Override
        public synchronized void write(int value) {
            if (delegate.size() >= limit) {
                truncated = true;
                return;
            }
            delegate.write(value);
        }

        @Override
        public synchronized void write(byte[] buffer, int offset, int length) {
            if (buffer == null || length <= 0) return;
            int remaining = limit - delegate.size();
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            int accepted = Math.min(remaining, length);
            delegate.write(buffer, offset, accepted);
            if (accepted < length) truncated = true;
        }

        synchronized boolean truncated() {
            return truncated;
        }

        synchronized String utf8() throws Exception {
            return delegate.toString("UTF-8");
        }
    }
}
