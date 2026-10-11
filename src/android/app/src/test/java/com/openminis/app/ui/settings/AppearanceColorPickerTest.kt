package com.openminis.app.ui.settings

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-plan-board-color] [parseColorInput] 纯函数矩阵：外观取色器的
 * HEX/RGB 输入是 2c1f1f2（per-mode RGB customization）新引入的解析面，
 * 此前零测试。钉住：
 *
 * - #RRGGBB（alpha=FF）/ #AARRGGBB（前 2 位 alpha）/ 0x|0X 前缀 / 裸 hex
 * - R,G,B 十进制 0..255（容忍段内空格）
 * - 非法输入返回 null（预览块显错误轮廓，Apply 被门控）
 *
 * 边界语义：全透明 #00RRGGBB 是合法值不是「无覆盖」——「无覆盖」由
 * NO_COLOR_OVERRIDE(-1) 承载，与解析层无关。
 */
class AppearanceColorPickerTest {

    @Test fun hex6ImpliesOpaqueAlpha() {
        assertEquals(Color(0xFF123456L), parseColorInput("#123456"))
        assertEquals(Color(0xFFABCDEFL), parseColorInput("ABCDEF"))
        assertEquals(Color(0xFF000000L), parseColorInput("#000000"))
        assertEquals(Color(0xFFFFFFFFL), parseColorInput("#FFFFFF"))
    }

    @Test fun hex8KeepsExplicitAlpha() {
        assertEquals(Color(0x80123456L), parseColorInput("#80123456"))
        assertEquals(Color(0x00123456L), parseColorInput("#00123456"))
        assertEquals(Color(0xFF123456L), parseColorInput("#FF123456"))
    }

    @Test fun hexPrefixVariantsStripped() {
        assertEquals(Color(0xFF0000FFL), parseColorInput("0x0000FF"))
        assertEquals(Color(0xFF00FF00L), parseColorInput("0X00FF00"))
        // 链式剥前缀：# 与 0x 组合也容忍（现状钉死，防回归）。
        assertEquals(Color(0xFF123456L), parseColorInput("#0x123456"))
        // 大小写 hex 均可。
        assertEquals(Color(0xFFABCDEFL), parseColorInput("#aBcDeF"))
    }

    @Test fun rgbDecimalParses() {
        assertEquals(Color(0xFFFF8000L), parseColorInput("255,128,0"))
        assertEquals(Color(0xFF000000L), parseColorInput("0, 0, 0"))
        assertEquals(Color(0xFF0000FFL), parseColorInput("0,0,255"))
        assertEquals(Color(0xFF64C896L), parseColorInput("100, 200, 150"))
    }

    @Test fun invalidInputsReturnNull() {
        val bad = listOf(
            "", "   ", "#", "0x", "0X",
            "GGGGGG", "#GG1234", "0xZZ",
            "12345", "1234567", "123456789",
            "256,0,0", "0,0,256", "-1,0,0", "0,-1,0",
            "1,2", "1,2,3,4", "a,b,c", "1,2,x",
            "0x0x123456", // 前缀只剥一次，第二个 0x 留在 hex 里 → 不匹配
        )
        for (input in bad) {
            assertNull("expected null for input: \"$input\"", parseColorInput(input))
        }
    }
}
