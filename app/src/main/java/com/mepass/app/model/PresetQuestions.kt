package com.mepass.app.model

/**
 * 预设隐私问题库
 *
 * 12 个精心设计的中文隐私问题，涵盖个人记忆、习惯、偏好等，
 * 答案稳定且不易被他人知晓。
 */
object PresetQuestions {
    val all: List<Question> = listOf(
        Question("preset_1", "你暗恋的人的名字", hint = "完整的姓名"),
        Question("preset_2", "你最喜欢的书的名字", hint = "书名全称"),
        Question("preset_3", "你被骗过多少钱（记忆最深刻的一次）", hint = "纯数字，如 5000"),
        Question("preset_4", "你丢过多少钱（记忆最深刻的一次）", hint = "纯数字，如 200"),
        Question("preset_5", "你有一个最常用的密码", hint = "完整密码字符串"),
        Question("preset_6", "你的坏习惯（两个字）", hint = "如 拖延、熬夜"),
        Question("preset_7", "你给自己起的真名（两个字）", hint = "两个字的化名"),
        Question("preset_8", "洗澡时通常先冲身体哪个部位（两个字）", hint = "如 头部、左肩"),
        Question("preset_9", "你未出生的孩子叫什么（两个字）", hint = "两个字的备选名字"),
        Question("preset_10", "妈妈给你的小名（两个字）", hint = "两个字的乳名"),
        Question("preset_11", "你的性癖（两个字）", hint = "两个字的描述"),
        Question("preset_12", "你的隐疾（五个字）", hint = "五个字的疾病名")
    )

    fun getById(id: String): Question? = all.find { it.id == id }
    val ids: List<String> get() = all.map { it.id }
}
