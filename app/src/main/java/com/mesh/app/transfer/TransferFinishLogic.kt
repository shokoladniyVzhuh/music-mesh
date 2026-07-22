package com.mesh.app.transfer

/**
 * Pure finish-phase rules for receiver (unit-tested).
 */
object TransferFinishLogic {
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
