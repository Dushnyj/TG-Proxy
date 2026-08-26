package com.dushnyj.tgproxy;

import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TofuHostKeyRepositoryTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void firstKeyIsPersistedAndChangedKeyIsRejected() throws Exception {
        File knownHosts = temporaryFolder.newFile("known_hosts");
        byte[] first = ed25519Key((byte) 1);
        byte[] changed = ed25519Key((byte) 2);

        TofuHostKeyRepository firstRun = repository(knownHosts, true);
        assertEquals(HostKeyRepository.OK, firstRun.check("relay.example", first));
        assertTrue(knownHosts.length() > 0);

        TofuHostKeyRepository secondRun = repository(knownHosts, true);
        assertEquals(HostKeyRepository.OK, secondRun.check("relay.example", first));
        assertEquals(HostKeyRepository.CHANGED, secondRun.check("relay.example", changed));
        assertEquals(HostKeyRepository.CHANGED,
                secondRun.check("relay.example", rsaKey((byte) 4)));

        TofuHostKeyRepository thirdRun = repository(knownHosts, true);
        assertEquals(HostKeyRepository.OK, thirdRun.check("relay.example", first));
        assertEquals(HostKeyRepository.CHANGED, thirdRun.check("relay.example", changed));
    }

    @Test
    public void clientCanForgetPinnedHostIncludingCustomPort() throws Exception {
        File knownHosts = temporaryFolder.newFile("known_hosts");
        String host = JschVpsSshClient.repositoryHost("relay.example", 2222);
        assertEquals(HostKeyRepository.OK,
                repository(knownHosts, true).check(host, ed25519Key((byte) 3)));

        JschVpsSshClient client = new JschVpsSshClient(knownHosts);
        assertTrue(client.forgetHost("relay.example", 2222));
        assertFalse(client.forgetHost("relay.example", 2222));
    }

    @Test
    public void firstKeyRequiresExplicitApprovalAndShowsSha256Fingerprint() throws Exception {
        File knownHosts = temporaryFolder.newFile("known_hosts-confirmation");
        byte[] key = ed25519Key((byte) 7);
        final String[] observed = new String[3];
        JSch jsch = new JSch();
        jsch.setKnownHosts(knownHosts.getAbsolutePath());
        TofuHostKeyRepository rejected = new TofuHostKeyRepository(
                jsch.getHostKeyRepository(), (host, algorithm, fingerprint) -> {
            observed[0] = host;
            observed[1] = algorithm;
            observed[2] = fingerprint;
            return false;
        });

        assertEquals(HostKeyRepository.NOT_INCLUDED, rejected.check("relay.example", key));
        assertEquals(0, knownHosts.length());
        assertEquals("relay.example", observed[0]);
        assertEquals("ssh-ed25519", observed[1]);
        assertTrue(observed[2].startsWith("SHA256:"));
    }

    private static TofuHostKeyRepository repository(File knownHosts, boolean approve)
            throws Exception {
        JSch jsch = new JSch();
        jsch.setKnownHosts(knownHosts.getAbsolutePath());
        return new TofuHostKeyRepository(jsch.getHostKeyRepository(),
                (host, algorithm, fingerprint) -> approve);
    }

    private static byte[] ed25519Key(byte fill) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        byte[] algorithm = "ssh-ed25519".getBytes("UTF-8");
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = fill;
        out.writeInt(algorithm.length);
        out.write(algorithm);
        out.writeInt(key.length);
        out.write(key);
        out.close();
        return buffer.toByteArray();
    }

    private static byte[] rsaKey(byte fill) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        byte[] algorithm = "ssh-rsa".getBytes("UTF-8");
        byte[] exponent = new byte[]{1, 0, 1};
        byte[] modulus = new byte[32];
        for (int i = 0; i < modulus.length; i++) modulus[i] = fill;
        out.writeInt(algorithm.length);
        out.write(algorithm);
        out.writeInt(exponent.length);
        out.write(exponent);
        out.writeInt(modulus.length);
        out.write(modulus);
        out.close();
        return buffer.toByteArray();
    }
}
