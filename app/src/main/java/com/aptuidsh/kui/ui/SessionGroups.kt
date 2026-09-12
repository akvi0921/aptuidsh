package com.aptuidsh.kui.ui

/**
 * 会话列表分组派生(移植官方 deepseek-harness web UI 的 workspace 树语义,
 * 见官方 packages/client/ui-workspace/src/client/tree.ts)。
 *
 * <p>官方规则(对齐):
 * <ul>
 *   <li>可见性:子代理会话不占普通行(经父会话 lineage 呈现)、blank 会话仅当前显示。</li>
 *   <li>工作区分组:按 workspace.list 稳定顺序,members 依 workspace.sessionIds
 *       顺序;未入任何工作区的会话落入「未分组」桶,按最近更新。</li>
 *   <li>组内/单列表排序:官方默认 orderBy=updated → 按 updatedAt 倒序(最新优先)。</li>
 *   <li>运行子代理徽标:父行显示其运行中的子代理后代数(runningSubagentCount)。</li>
 * </ul>
 * 本端为自适应:未打开的子代理行不显示,但<b>当前选中的子代理会话仍可见</b>
 * (兜底入口,避免用户在 Android 端无法回到正在查看的子代理窗口)。
 */

/** 分组模式(官方两档):按工作区 / 单列表。 */
enum class SessionGroupBy { WORKSPACE, FLAT }

/** 未分组桶 key(官方 UNGROUPED_KEY='')。 */
const val UNGROUPED_KEY = ""

/** 未分组桶显示名(官方 locale group.ungrouped)。 */
const val UNGROUPED_LABEL = "未分组"

/** 工作区树中一个组 section:折叠时仅组头;展开时含可见行。 */
data class SessionGroup(
    val key: String,            // workspaceId;未分组桶为 UNGROUPED_KEY
    val label: String,          // 工作区 title(basename);未分组为「未分组」
    val cwd: String,            // 工作区 path(未分组为空)
    val sessionCount: Int,      // 组内可见会话总数
    val expanded: Boolean,      // 组头折叠/展开(展开才渲染行)
    val containsCurrent: Boolean, // 组是否含当前会话(激活色提示)
    val sessions: List<SessionState>, // 可见行(expanded 时才有;按 updatedAt 倒序)
)

/**
 * 官方可见性:非子代理(或当前选中的子代理兜底)&& (非 blank || 是当前会话)。
 */
fun sessionVisible(s: SessionState, currentId: String): Boolean {
    val notSubagent = s.origin != "subagent" || s.id == currentId
    val notHiddenBlank = !s.blank || s.id == currentId
    return notSubagent && notHiddenBlank
}

/**
 * 派生工作区树组列表(官方 groupByWorkspace + deriveGroups)。
 * 组顺序 = workspace.list 顺序;游离会话 → 末尾「未分组」桶。
 */
fun deriveWorkspaceGroups(
    sessions: Collection<SessionState>,
    workspaces: List<WorkspaceEntry>,
    currentId: String,
    expandedKeys: Set<String>,
): List<SessionGroup> {
    val byId = sessions.associateBy { it.id }
    val groups = ArrayList<SessionGroup>()
    val accounted = HashSet<String>()
    for (w in workspaces) {
        val members = ArrayList<SessionState>()
        for (id in w.sessionIds) {
            val s = byId[id] ?: continue
            accounted.add(id)
            if (!sessionVisible(s, currentId)) continue
            members.add(s)
        }
        groups.add(
            SessionGroup(
                key = w.workspaceId,
                label = w.title,
                cwd = w.path,
                sessionCount = members.size,
                expanded = expandedKeys.contains(w.workspaceId),
                containsCurrent = w.sessionIds.contains(currentId),
                sessions = members.sortedByDescending { it.updatedAt },
            ),
        )
    }
    val stray = sessions
        .filter { !accounted.contains(it.id) && sessionVisible(it, currentId) }
        .sortedByDescending { it.updatedAt }
    if (stray.isNotEmpty()) {
        groups.add(
            SessionGroup(
                key = UNGROUPED_KEY,
                label = UNGROUPED_LABEL,
                cwd = "",
                sessionCount = stray.size,
                expanded = expandedKeys.contains(UNGROUPED_KEY),
                containsCurrent = sessions.any { it.id == currentId && !accounted.contains(currentId) },
                sessions = stray,
            ),
        )
    }
    return groups
}

/** flat 单列表:全部可见会话按 updatedAt 倒序(官方 deriveFlat + byRecency)。 */
fun deriveFlatSessions(sessions: Collection<SessionState>, currentId: String): List<SessionState> =
    sessions.filter { sessionVisible(it, currentId) }.sortedByDescending { it.updatedAt }

/**
 * 运行中的子代理后代数(官方 indexSubagentDescendants → runningCount)。
 * 对每个运行中的子代理,沿 parentSessionId 链向上累加,直到最近的主会话祖先;
 * 返回 key=主会话 id → 运行子代理数(父行徽标)。
 */
fun runningSubagentCounts(sessions: Collection<SessionState>): Map<String, Int> {
    val byId = sessions.associateBy { it.id }
    val counts = HashMap<String, Int>()
    for (s in sessions) {
        if (s.origin != "subagent" || !s.running) continue
        var anchor = byId[s.parentSessionId]
        while (anchor != null) {
            counts[anchor.id] = (counts[anchor.id] ?: 0) + 1
            if (anchor.origin == "subagent") {
                anchor = byId[anchor.parentSessionId]
            } else {
                break
            }
        }
    }
    return counts
}
