package io.github.jakeranch.voiceagent

/**
 * 内置技能指引（移植 nanoMuse skills/builtin 的思路）：
 * 按指令关键词给大脑注入对应领域的操作步骤提示，提高特定任务成功率。
 */
object Skills {

    private val SKILLS = listOf(
        (listOf("导航", "路线", "怎么去", "开车去", "多远", "多久到", "去哪") to
            "导航技能：打开地图类应用（高德/百度），搜索目的地，选驾车/公交路线，点开始导航；没装地图应用就告诉主人。"),
        (listOf("快递", "包裹", "物流") to
            "快递技能：打开菜鸟或支付宝的快递功能，读出最近的包裹状态。"),
        (listOf("提醒", "叫醒", "闹钟", "分钟后", "小时后") to
            "提醒技能：用 set_alarm 一步设置 X 分钟后的提醒，不要再打开时钟应用。"),
        (listOf("日历", "日程", "会议", "安排") to
            "日历技能：确认事项和时间后简洁回复主人。"),
        (listOf("外卖", "点餐", "点外卖", "点个") to
            "外卖技能：打开美团/饿了么，搜索店铺或食物，加入购物车，到支付前停下让主人自己完成付款。"),
        (listOf("打车", "叫车") to
            "打车技能：打开滴滴或高德打车，输入起点终点，确认车型，到支付前停下让主人自己完成。"),
        (listOf("微信", "发消息", "发微信") to
            "微信技能：打开微信，搜索联系人，输入内容，发送。"),
        (listOf("打电话", "拨号", "电话给") to
            "打电话技能：打开电话/联系人应用，找到联系人拨出。"),
    )

    /** 返回第一条命中的技能指引；无命中返回 null。 */
    fun match(cmd: String): String? {
        for ((keywords, snippet) in SKILLS) {
            if (keywords.any { cmd.contains(it) }) return snippet
        }
        return null
    }
}
