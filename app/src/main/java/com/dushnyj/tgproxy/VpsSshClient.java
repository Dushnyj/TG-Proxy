package com.dushnyj.tgproxy;

interface VpsSshClient {
    String execute(VpsSshCredentials credentials, VpsSetupProgress.Stage stage,
                   String command, String stdin, int timeoutMs) throws Exception;
}
