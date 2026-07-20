package com.example.cf.file.domain.repository

import com.example.cf.file.domain.model.FileObject
import com.example.cf.shared.kernel.id.FileId
import java.time.Instant

interface FileObjectRepository {
    fun findById(id: FileId): FileObject?

    /** 完了処理用に悲観ロックで取得する（§5.5）。 */
    fun findByIdForUpdate(id: FileId): FileObject?

    /** BAT-008 ファイル清掃の対象（PENDINGかつ失効済み）を取得する。 */
    fun lockExpiredPendingBatch(now: Instant, limit: Int): List<FileObject>

    fun save(file: FileObject)
}
