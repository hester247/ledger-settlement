public class ContainerErgonomicsCheck {
    public static void main(String[] args) {
        Runtime runtime = Runtime.getRuntime();

        long maxMemoryBytes = runtime.maxMemory(); // Corresponds roughly to -Xmx
        long totalMemoryBytes = runtime.totalMemory(); // Currently allocated heap
        long freeMemoryBytes = runtime.freeMemory();
        int availableProcessors = runtime.availableProcessors();

        System.out.println("=== Container & JVM Memory Diagnostics ===");
        System.out.printf("Available Processors (CPU Limit): %d%n", availableProcessors);
        System.out.printf("Max Heap (-Xmx limit): %.2f MB%n", maxMemoryBytes / (1024.0 * 1024.0));
        System.out.printf("Total Allocated Heap:  %.2f MB%n", totalMemoryBytes / (1024.0 * 1024.0));
        System.out.printf("Free Heap Space:       %.2f MB%n", freeMemoryBytes / (1024.0 * 1024.0));
    }
}
