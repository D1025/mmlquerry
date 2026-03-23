package mag.mizarstack.ingest.service;

record IndexFileResult(
        Long documentId,
        Long versionId,
        boolean isNewVersion,
        long sizeBytes
) {
}



