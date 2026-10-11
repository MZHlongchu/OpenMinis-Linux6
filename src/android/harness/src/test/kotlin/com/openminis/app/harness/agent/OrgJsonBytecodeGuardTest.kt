package com.openminis.app.harness.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * [T-guard-app-root] 守卫内核回归（不依赖 Android 工具链，纯 JVM）：
 *   1. 扫描根筛选——单测/夹具变体目录永不进扫描集合，设备变体照常扫；
 *   2. 常量池解析——被禁引用命中、同接收者的合法成员不误伤、未知 tag 显式报错。
 *
 * 夹具是手工拼的「只有常量池」的最小 class 字节流：OrgJsonBytecodeGuard.scan
 * 只读常量池，不需要方法区，所以没必要（也没法在纯 JVM 单测里）编真类。
 */
class OrgJsonBytecodeGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `unit-test and fixture roots are excluded from device scan roots`() {
        val root = tmp.newFolder("kotlin-classes")
        val ok = probeClass("org/json/JSONArray", "get", "(I)Ljava/lang/Object;")
        for (name in listOf(
            "fullRelease", "slimRelease",
            "fullReleaseUnitTest", "slimReleaseUnitTest", "releaseUnitTest",
            "fullReleaseTestFixtures",
        )) {
            writeClass(File(root, name), "Sample.class", ok)
        }
        // AGP 会留下空壳目录（本机 3 个变体目录全是 0 个 class），不算扫描根
        File(root, "fullDebug").mkdirs()

        assertEquals(
            listOf(
                "fullRelease", "fullReleaseTestFixtures", "fullReleaseUnitTest",
                "releaseUnitTest", "slimRelease", "slimReleaseUnitTest",
            ),
            OrgJsonBytecodeGuard.classRoots(root).map { it.name },
        )
        assertEquals(
            listOf("fullRelease", "slimRelease"),
            OrgJsonBytecodeGuard.deviceClassRoots(root).map { it.name },
        )
    }

    @Test
    fun `test-only marker matches AGP test variants but not device variants`() {
        for (name in listOf(
            "fullReleaseUnitTest", "slimReleaseUnitTest", "releaseUnitTest",
            "fullDebugUnitTest", "fullReleaseTestFixtures", "slimReleaseTestFixtures",
        )) {
            assertTrue("$name 只跑 JVM，应排除", OrgJsonBytecodeGuard.isTestOnlyClassRoot(name))
        }
        // *AndroidTest 是设备字节码（真机 core-libart org.json），不在排除标记里
        for (name in listOf(
            "fullRelease", "slimRelease", "release", "fullDebug", "fullReleaseAndroidTest",
        )) {
            assertFalse("$name 是设备产物，不应排除", OrgJsonBytecodeGuard.isTestOnlyClassRoot(name))
        }
    }

    @Test
    fun `banned call is reported in device roots and never reached in a unit-test root`() {
        val root = tmp.newFolder("kotlin-classes")
        val banned = probeClass("org/json/JSONArray", "toList", "()Ljava/util/List;")
        writeClass(File(root, "fullRelease"), "Offender.class", banned)
        // 同样的字节流放在单测夹具目录里：合法（桌面 org.json）且永不进 APK
        writeClass(File(root, "fullReleaseUnitTest"), "Fixture.class", banned)

        val deviceRoots = OrgJsonBytecodeGuard.deviceClassRoots(root)
        assertEquals(listOf("fullRelease"), deviceRoots.map { it.name })
        assertEquals(
            listOf("Offender.class: org/json/JSONArray.toList"),
            OrgJsonBytecodeGuard.scanAll(deviceRoots),
        )
        // 单测目录一旦被混进扫描集合就会误报（同一个字节流命中两次）——这正是本次修掉的回归
        assertEquals(
            listOf(
                "Fixture.class: org/json/JSONArray.toList",
                "Offender.class: org/json/JSONArray.toList",
            ),
            OrgJsonBytecodeGuard.scanAll(OrgJsonBytecodeGuard.classRoots(root)),
        )
    }

    @Test
    fun `legit members on the same receivers are not flagged`() {
        val root = tmp.newFolder("kotlin-classes")
        val fullRelease = File(root, "fullRelease")
        writeClass(
            fullRelease, "Get.class",
            probeClass("org/json/JSONArray", "get", "(I)Ljava/lang/Object;"),
        )
        writeClass(
            fullRelease, "Kt.class",
            probeClass("kotlin/collections/CollectionsKt", "toList", "(Ljava/lang/Iterable;)Ljava/util/List;"),
        )
        writeClass(
            fullRelease, "Seq.class",
            probeClass("kotlin/sequences/SequencesKt", "toList", "(Lkotlin/sequences/Sequence;)Ljava/util/List;"),
        )
        writeClass(
            fullRelease, "Owner.class",
            probeClass("com/openminis/app/Fixture", "toMap", "()Ljava/util/Map;"),
        )

        assertTrue(
            "同接收者的合法成员不应被误伤",
            OrgJsonBytecodeGuard.scanAll(OrgJsonBytecodeGuard.deviceClassRoots(root)).isEmpty(),
        )
    }

    @Test
    fun `sequenced-collection family stays banned on List receivers`() {
        val root = tmp.newFolder("kotlin-classes")
        writeClass(
            File(root, "fullRelease"), "Queue.class",
            probeClass("java/util/List", "removeFirst", "()Ljava/lang/Object;"),
        )
        writeClass(
            File(root, "fullRelease"), "Map2.class",
            probeClass("java/util/SequencedCollection", "reversed", "()Ljava/util/SequencedCollection;"),
        )
        assertEquals(
            listOf(
                "Map2.class: java/util/SequencedCollection.reversed",
                "Queue.class: java/util/List.removeFirst",
            ),
            OrgJsonBytecodeGuard.scanAll(OrgJsonBytecodeGuard.deviceClassRoots(root)),
        )
    }

    @Test
    fun `unknown constant-pool tag fails loudly instead of skipping the file`() {
        val root = tmp.newFolder("kotlin-classes")
        // 12 字节：magic + minor + major + cp_count=2 + 非法 tag(99) + 填充
        val broken = File(root, "Broken.class")
        broken.writeBytes(
            byteArrayOf(
                0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
                0, 0, 0, 52, 0, 2, 99, 0,
            ),
        )
        val thrown = runCatching { OrgJsonBytecodeGuard.scan(broken) }.exceptionOrNull()
        assertTrue(
            "未知常量池 tag 必须显式报错，静默跳过等于守卫瞎了：$thrown",
            thrown is IllegalStateException,
        )
    }

    private fun writeClass(dir: File, fileName: String, bytes: ByteArray) {
        dir.mkdirs()
        File(dir, fileName).writeBytes(bytes)
    }

    /**
     * 拼一个「只有常量池」的最小 class：先塞满解析器 when 的每个 tag 分支
     * （long/double 双槽最容易把索引算错），最后塞一条 owner.name 的 Methodref。
     * 目标引用只此一条，所以每个文件最多命中一次（对照引用都指向不受禁的接收者）。
     */
    private fun probeClass(owner: String, name: String, descriptor: String): ByteArray {
        val cp = ConstantPool()
        cp.integer(7)
        cp.float(1.5f)
        cp.long(42L)
        cp.double(2.5)
        cp.string(cp.utf8("filler"))
        // 对照引用：覆盖 Fieldref / InterfaceMethodref / MethodHandle / MethodType 分支
        val probeCls = cp.clazz(cp.utf8("com/openminis/app/Probe"))
        val probeDesc = cp.utf8("()Ljava/lang/String;")
        val probeNat = cp.nameAndType(cp.utf8("describe"), probeDesc)
        cp.fieldRef(probeCls, probeNat)
        cp.interfaceMethodRef(probeCls, probeNat)
        val handle = cp.methodHandle(6, cp.fieldRef(probeCls, probeNat))
        cp.methodType(probeDesc)
        cp.dynamic(handle, probeNat)
        cp.invokeDynamic(handle, probeNat)
        cp.module(cp.utf8("probe.module"))
        cp.packageEntry(cp.utf8("probe/package"))
        // 目标引用
        cp.methodRef(cp.clazz(cp.utf8(owner)), cp.nameAndType(cp.utf8(name), cp.utf8(descriptor)))
        return cp.build()
    }

    /** class 文件常量池写入器：索引从 1 开始，long/double 占双槽。 */
    private class ConstantPool {
        private val body = ByteArrayOutputStream()
        private val out = DataOutputStream(body)
        private var size = 1

        fun utf8(s: String): Int { out.writeByte(1); out.writeUTF(s); return size++ }
        fun integer(v: Int): Int { out.writeByte(3); out.writeInt(v); return size++ }
        fun float(v: Float): Int { out.writeByte(4); out.writeFloat(v); return size++ }
        fun long(v: Long): Int { out.writeByte(5); out.writeLong(v); return size.also { size += 2 } }
        fun double(v: Double): Int { out.writeByte(6); out.writeDouble(v); return size.also { size += 2 } }
        fun clazz(nameIdx: Int): Int { out.writeByte(7); out.writeShort(nameIdx); return size++ }
        fun string(utf8Idx: Int): Int { out.writeByte(8); out.writeShort(utf8Idx); return size++ }
        fun fieldRef(classIdx: Int, natIdx: Int): Int {
            out.writeByte(9); out.writeShort(classIdx); out.writeShort(natIdx); return size++
        }
        fun methodRef(classIdx: Int, natIdx: Int): Int {
            out.writeByte(10); out.writeShort(classIdx); out.writeShort(natIdx); return size++
        }
        fun interfaceMethodRef(classIdx: Int, natIdx: Int): Int {
            out.writeByte(11); out.writeShort(classIdx); out.writeShort(natIdx); return size++
        }
        fun nameAndType(nameIdx: Int, descIdx: Int): Int {
            out.writeByte(12); out.writeShort(nameIdx); out.writeShort(descIdx); return size++
        }
        fun methodHandle(kind: Int, refIdx: Int): Int {
            out.writeByte(15); out.writeByte(kind); out.writeShort(refIdx); return size++
        }
        fun methodType(descIdx: Int): Int { out.writeByte(16); out.writeShort(descIdx); return size++ }
        fun dynamic(bsmIdx: Int, natIdx: Int): Int {
            out.writeByte(17); out.writeShort(bsmIdx); out.writeShort(natIdx); return size++
        }
        fun invokeDynamic(bsmIdx: Int, natIdx: Int): Int {
            out.writeByte(18); out.writeShort(bsmIdx); out.writeShort(natIdx); return size++
        }
        fun module(nameIdx: Int): Int { out.writeByte(19); out.writeShort(nameIdx); return size++ }
        fun packageEntry(nameIdx: Int): Int { out.writeByte(20); out.writeShort(nameIdx); return size++ }

        fun build(): ByteArray {
            val head = ByteArrayOutputStream()
            val header = DataOutputStream(head)
            header.writeInt(0xCAFEBABE.toInt())
            header.writeShort(0) // minor
            header.writeShort(52) // major
            header.writeShort(size) // constant_pool_count
            return head.toByteArray() + body.toByteArray()
        }
    }
}
