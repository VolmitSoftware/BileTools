package com.volmit.bile;

public record RemoteDeployTarget(String host, int port) {
    public static RemoteDeployTarget parse(String value) {
        String trimmed = value == null ? "" : value.trim();
        int separator = trimmed.lastIndexOf(':');
        if (separator <= 0 || separator == trimmed.length() - 1) {
            throw new IllegalArgumentException("expected host:port");
        }
        String host = trimmed.substring(0, separator).trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isEmpty() || (host.indexOf(':') >= 0 && !trimmed.startsWith("["))) {
            throw new IllegalArgumentException("expected host:port");
        }
        int port;
        try {
            port = Integer.parseInt(trimmed.substring(separator + 1).trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("port must be a number");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        return new RemoteDeployTarget(host, port);
    }

    @Override
    public String toString() {
        return host.indexOf(':') >= 0 ? "[" + host + "]:" + port : host + ":" + port;
    }
}
