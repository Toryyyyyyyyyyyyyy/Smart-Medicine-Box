/*
 * Copyright (c) 2026 Tory_zy Tu
 */

package com.smartmedicinebox.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MedicineEntity::class, RecordEntity::class, PendingDoseEntity::class],
    version = 13,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun medicineDao(): MedicineDao
    abstract fun recordDao(): RecordDao
    abstract fun pendingDoseDao(): PendingDoseDao

    companion object {
        /** v1 → v2：给药品表新增自定义铃声列 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicines ADD COLUMN ringtoneUri TEXT")
            }
        }

        /** v2 → v3：给药品表新增重复星期列（空=每天） */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicines ADD COLUMN repeatDays TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v3 → v4：给药品表新增重复模式 / 间隔天数 / 起始日 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicines ADD COLUMN repeatMode TEXT NOT NULL DEFAULT 'weekday'")
                db.execSQL("ALTER TABLE medicines ADD COLUMN intervalDays INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE medicines ADD COLUMN anchorDay INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v4 → v5：给药品表新增删除时间戳（回收站） */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicines ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v5 → v6：给记录表新增药品名称快照 */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE records ADD COLUMN medicineName TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v6 → v7：新增待补服「未服用」剂量表 */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_doses` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`medicineId` INTEGER NOT NULL, " +
                        "`medicineName` TEXT NOT NULL, " +
                        "`scheduledTime` INTEGER NOT NULL)"
                )
            }
        }

        /** v7 → v8：药品/记录表新增客户端 UUID（E2E 跨设备合并主键） */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicines ADD COLUMN clientUuid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE records ADD COLUMN clientUuid TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_medicines_clientUuid ON medicines(clientUuid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_records_clientUuid ON records(clientUuid)")
            }
        }

        /** v8 → v9：给药品表新增自定义视频裁剪区域列 */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicines ADD COLUMN videoCrop TEXT")
            }
        }

        /** v9 → v10：给记录表新增删除时间戳（软删除标记，跨设备双向同步删除） */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE records ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v10 → v11：给记录表新增撤销资格标记（药品闹钟时间变更后冻结该药旧记录，不可撤销） */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE records ADD COLUMN undoable INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** v11 → v12：给药品表新增「是否进入过回收站」标记（进入回收站后永久冻结该药记录撤销资格） */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicines ADD COLUMN hasBeenTrashed INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v12 → v13：给药品表新增状态重置时间（从回收站恢复后视为全新药品，忽略恢复前的服药进度） */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicines ADD COLUMN stateResetAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "medicine_box.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
