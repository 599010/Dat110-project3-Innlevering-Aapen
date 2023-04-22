package no.hvl.dat110.util;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Hash {

    private static final int MD5_DIGEST_LENGTH = 16;

    public static BigInteger hashOf(String entity) {

        BigInteger hashint = null;

        try {
            // Compute the MD5 hash of the input 'entity'
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(entity.getBytes("UTF-8"));

            // Convert the hash into hex format
            String hex = toHex(digest);

            // Convert the hex into BigInteger
            hashint = new BigInteger(hex, 16);

        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        // Return the BigInteger
        return hashint;
    }

    public static BigInteger addressSize() {

        // Compute the number of bits in the MD5 digest
        int bits = bitSize();

        // Compute the address size = 2 ^ number of bits
        BigInteger size = BigInteger.valueOf(2).pow(bits);

        // Return the address size
        return size;
    }

    public static int bitSize() {

        // Find the digest length of MD5
        int digestlen = MD5_DIGEST_LENGTH;

        // Return the number of bits
        return digestlen * 8;
    }

    public static String toHex(byte[] digest) {
        StringBuilder strbuilder = new StringBuilder();
        for(byte b : digest) {
            strbuilder.append(String.format("%02x", b&0xff));
        }
        return strbuilder.toString();
    }
}
