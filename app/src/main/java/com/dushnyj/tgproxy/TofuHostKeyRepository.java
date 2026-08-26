package com.dushnyj.tgproxy;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.UserInfo;

import java.security.MessageDigest;
import org.bouncycastle.util.encoders.Base64;

/**
 * Trusts a host key only on the first connection and delegates persistence to JSch's KnownHosts.
 * A different key for an already pinned host is never accepted automatically.
 */
final class TofuHostKeyRepository implements HostKeyRepository {
    interface Approver {
        boolean approve(String host, String algorithm, String fingerprint);
    }

    private final HostKeyRepository delegate;
    private final Approver approver;

    TofuHostKeyRepository(HostKeyRepository delegate) {
        this(delegate, null);
    }

    TofuHostKeyRepository(HostKeyRepository delegate, Approver approver) {
        if (delegate == null) throw new IllegalArgumentException("delegate == null");
        this.delegate = delegate;
        this.approver = approver;
    }

    @Override
    public synchronized int check(String host, byte[] key) {
        int result = delegate.check(host, key);
        if (result != NOT_INCLUDED) return result;
        // KnownHosts reports NOT_INCLUDED when the host is known under another key algorithm.
        // Treat that as a changed key too; otherwise an attacker could bypass the pin by
        // presenting a different algorithm.
        if (delegate.getHostKey(host, null).length > 0) return CHANGED;
        try {
            HostKey candidate = new HostKey(host, key);
            if (approver == null || !approver.approve(
                    host, candidate.getType(), sha256Fingerprint(key))) {
                return NOT_INCLUDED;
            }
            delegate.add(candidate, null);
        } catch (JSchException | RuntimeException e) {
            return NOT_INCLUDED;
        }
        return delegate.check(host, key);
    }

    @Override
    public synchronized void add(HostKey hostKey, UserInfo userInfo) {
        delegate.add(hostKey, userInfo);
    }

    @Override
    public synchronized void remove(String host, String type) {
        delegate.remove(host, type);
    }

    @Override
    public synchronized void remove(String host, String type, byte[] key) {
        delegate.remove(host, type, key);
    }

    @Override
    public synchronized String getKnownHostsRepositoryID() {
        return delegate.getKnownHostsRepositoryID();
    }

    @Override
    public synchronized HostKey[] getHostKey() {
        return delegate.getHostKey();
    }

    @Override
    public synchronized HostKey[] getHostKey(String host, String type) {
        return delegate.getHostKey(host, type);
    }

    static String sha256Fingerprint(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    key == null ? new byte[0] : key);
            return "SHA256:" + Base64.toBase64String(digest).replace("=", "");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
