package com.mepass.app.model

/**
 * 12个预设隐私问题库
 */
object PresetQuestions {

    val presetQuestions: List<Question> = listOf(
        Question(
            id = "preset_1",
            text = "你暗恋的人的名字",
            isCustom = false,
            hint = "请输入全名或你心中的称呼"
        ),
        Question(
            id = "preset_2",
            text = "你最喜欢的书的名字",
            isCustom = false,
            hint = "请输入完整书名"
        ),
        Question(
            id = "preset_3",
            text = "你被骗过多少钱（记忆最深刻的一次）",
            isCustom = false,
            hint = "请输入数字金额，例如：5000"
        ),
        Question(
            id = "preset_4",
            text = "你丢过多少钱（记忆最深刻的一次）",
            isCustom = false,
            hint = "请输入数字金额，例如：300"
        ),
        Question(
            id = "preset_5",
            text = "你有一个最常用的密码",
            isCustom = false,
            hint = "你使用频率最高的那个密码"
        ),
        Question(
            id = "preset_6",
            text = "你的坏习惯（两个字）",
            isCustom = false,
            hint = "请输入两个字，例如：熬夜"
        ),
        Question(
            id = "preset_7",
            text = "你给自己起的真名（两个字）",
            isCustom = false,
            hint = "如果给自己改名，你会叫什么？两个字"
        ),
        Question(
            id = "preset_8",
            text = "洗澡时通常先冲身体哪个部位（两个字）",
            isCustom = false,
            hint = "请输入两个字，例如：头发"
        ),
        Question(
            id = "preset_9",
            text = "你未出生的孩子叫什么（两个字）",
            isCustom = false,
            hint = "请输入两个字的名字，例如：子涵"
        ),
        Question(
            id = "preset_10",
            text = "妈妈给你的小名（两个字）",
            isCustom = false,
            hint = "童年时妈妈常叫你的名字"
        ),
        Question(
            id = "preset_11",
            text = "你的性癖（两个字）",
            isCustom = false,
            hint = "两个字描述即可"
        ),
        Question(
            id = "preset_12",
            text = "你的隐疾（五个字）",
            isCustom = false,
            hint = "五个字描述，例如：过敏性鼻炎"
        )
    )

    fun getById(id: String): Question? = presetQuestions.find { it.id == id }

    fun getPresetIds(): List<String> = presetQuestions.map { it.id }
}
