package mag.mizarstack.ingest;

record IndexFileResult(
        Long documentId,
        Long versionId,
        boolean isNewVersion,
        long sizeBytes
) {
}

