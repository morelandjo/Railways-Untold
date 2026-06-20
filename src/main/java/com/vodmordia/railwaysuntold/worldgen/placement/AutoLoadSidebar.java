package com.vodmordia.railwaysuntold.worldgen.placement;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.*;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.List;

/**
 * Left-justified sidebar list of live auto-load stats - one stat per line. Backed by a scoreboard
 * objective in the sidebar slot; each row's text is carried by the score's display name (with blank
 * number formatting), so rows update in place without flicker. The objective is removed when auto-load
 * stops and any copy left by a crashed session is cleared on boot, so it never lingers in the world's
 * saved scoreboard. Whatever sidebar was showing beforehand is restored on hide.
 */
final class AutoLoadSidebar {

    private static final String OBJECTIVE_NAME = "ru_autoload";

    private Objective objective;
    private Objective restoreOnHide;

    void update(MinecraftServer server, Component title, List<String> lines) {
        Scoreboard scoreboard = server.getScoreboard();
        if (objective == null) {
            restoreOnHide = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
            Objective stale = scoreboard.getObjective(OBJECTIVE_NAME);
            if (stale != null) scoreboard.removeObjective(stale);
            objective = scoreboard.addObjective(OBJECTIVE_NAME, ObjectiveCriteria.DUMMY, title,
                    ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
            scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, objective);
        }
        objective.setDisplayName(title);
        for (int i = 0; i < lines.size(); i++) {
            ScoreHolder holder = ScoreHolder.forNameOnly("ru_line_" + i);
            ScoreAccess score = scoreboard.getOrCreatePlayerScore(holder, objective);
            score.set(lines.size() - i);   // higher score sorts nearer the top
            score.display(Component.literal(lines.get(i)));
        }
    }

    void hide(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();
        if (objective != null) {
            scoreboard.removeObjective(objective);
            objective = null;
        }
        if (restoreOnHide != null && scoreboard.getObjective(restoreOnHide.getName()) != null) {
            scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, restoreOnHide);
        }
        restoreOnHide = null;
    }

    /** Remove a copy left in the saved scoreboard by a previous (e.g. crashed) session. */
    void clearStale(MinecraftServer server) {
        Objective stale = server.getScoreboard().getObjective(OBJECTIVE_NAME);
        if (stale != null) server.getScoreboard().removeObjective(stale);
        objective = null;
        restoreOnHide = null;
    }
}
