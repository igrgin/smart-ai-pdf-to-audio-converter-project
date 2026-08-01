package dev.audiobook.platform.library;

record HttpByteRange(long start, long end) {

    static HttpByteRange parse(String header, long length) {
        if (length <= 0 || header == null || !header.startsWith("bytes=") || header.indexOf(',') >= 0) {
            throw new UnsatisfiedRangeException(length);
        }
        String value = header.substring("bytes=".length()).trim();
        int separator = value.indexOf('-');
        if (separator < 0 || separator != value.lastIndexOf('-')) {
            throw new UnsatisfiedRangeException(length);
        }
        try {
            String first = value.substring(0, separator).trim();
            String last = value.substring(separator + 1).trim();
            if (first.isEmpty()) {
                long suffixLength = Long.parseLong(last);
                if (suffixLength <= 0) {
                    throw new UnsatisfiedRangeException(length);
                }
                long bounded = Math.min(suffixLength, length);
                return new HttpByteRange(length - bounded, length - 1);
            }
            long start = Long.parseLong(first);
            long end = last.isEmpty() ? length - 1 : Math.min(Long.parseLong(last), length - 1);
            if (start < 0 || start >= length || end < start) {
                throw new UnsatisfiedRangeException(length);
            }
            return new HttpByteRange(start, end);
        } catch (NumberFormatException exception) {
            throw new UnsatisfiedRangeException(length);
        }
    }

    long length() {
        return end - start + 1;
    }
}
