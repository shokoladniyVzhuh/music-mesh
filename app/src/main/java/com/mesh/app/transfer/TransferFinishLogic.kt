package com.mesh.app.transfer

/**
 * Pure finish-phase rules for receiver (unit-tested).
 */
object TransferFinishLogic {
    fun validateReceiverResult(result: ControlMessage.TransferResult, requested: Int, skippedAtStart: Int) {
        require(result.transferred >= 0 && result.failed >= 0 && result.skipped >= skippedAtStart) {
            "Invalid transfer result counts"
        }
        require(result.transferred.toLong() + result.failed + result.skipped == requested.toLong() + skippedAtStart) {
            "Invalid transfer result"
        }
    }

    fun receiverCanFinishNormally(
        processed: Int,
        totalRequested: Int,
        inflightImports: Int,
    ): Boolean = inflightImports == 0 && processed >= totalRequested

    fun receiverPhase(
        transferred: Int,
        skipped: Int,
        failed: Int,
        processed: Int,
        totalRequested: Int,
    ): TransferPhase {
        if (totalRequested == 0) {
            return TransferPhase.Success(transferred = transferred, skipped = skipped)
        }
        if (failed > 0) {
            val missing = (totalRequested - processed).coerceAtLeast(0)
            return TransferPhase.PartialSuccess(
                transferred = transferred,
                skipped = skipped,
                failed = failed + missing,
                message = "Transfer finished with issues",
            )
        }
        if (processed >= totalRequested) {
            return TransferPhase.Success(transferred = transferred, skipped = skipped)
        }
        return TransferPhase.PartialSuccess(
            transferred = transferred,
            skipped = skipped,
            failed = (totalRequested - processed).coerceAtLeast(0),
            message = "Connection closed, transfer interrupted",
        )
    }
}
