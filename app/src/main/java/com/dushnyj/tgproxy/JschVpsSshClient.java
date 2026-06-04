package com.dushnyj.tgproxy;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

final class JschVpsSshClient implements VpsSshClient {
    @Override
    public String execute(VpsSshCredentials credentials, VpsSetupProgress.Stage stage,
                          String command, String stdin, int timeoutMs) throws Exception {
        if (credentials == null || !credentials.isValid()) {
            throw new VpsSetupException("invalid SSH credentials");
        }
        JSch jsch = new JSch();
        Session session = jsch.getSession(credentials.user(), credentials.host(), credentials.port());
        if (!credentials.password().isEmpty()) session.setPassword(credentials.password());
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", "password,keyboard-interactive,publickey");
        session.setConfig(config);
        session.connect(Math.min(timeoutMs, 20_000));
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command == null || command.trim().isEmpty() ? "sh -s" : command);
            if (stdin != null) {
                channel.setInputStream(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            channel.setOutputStream(out);
            channel.setErrStream(err);
            channel.connect(Math.min(timeoutMs, 20_000));
            long deadline = System.currentTimeMillis() + Math.max(1_000, timeoutMs);
            while (!channel.isClosed()) {
                if (System.currentTimeMillis() > deadline) {
                    throw new VpsSetupException("SSH command timeout");
                }
                Thread.sleep(100);
            }
            String stdout = out.toString("UTF-8");
            String stderr = err.toString("UTF-8");
            int exit = channel.getExitStatus();
            if (exit != 0) {
                throw new VpsSetupException(stderr.isEmpty()
                        ? "SSH command failed: " + exit
                        : stderr.trim());
            }
            return stdout;
        } finally {
            if (channel != null) channel.disconnect();
            session.disconnect();
        }
    }
}
