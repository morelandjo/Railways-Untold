package com.vodmordia.railwaysuntold.worldgen.placement;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.List;

/**
 * Left-justified sidebar list of live auto-load stats - one stat per line. 1.20.1 has no per-score
 * display name or number hiding, so each row's text is carried by a team prefix attached to an
 * effectively-invisible (colour-code) entry, and the score value orders the rows top-to-bottom (it
 * shows as a small number on the right - a vanilla 1.20.1 limitation, hidden on the 1.21.1 branch).
 * The objective and its helper teams are removed when auto-load stops and on boot, so nothing lingers
 * in the world's saved scoreboard.
 */
final class AutoLoadSidebar {

    private static final String OBJECTIVE_NAME = "ru_autoload";
    private static final int MAX_LINES = 8;

    private Objective objective;

    void update(MinecraftServer server, Component title, List<String> lines) {
        Scoreboard scoreboard = server.getScoreboard();
        if (objective == null) {
            Objective stale = scoreboard.getObjective(OBJECTIVE_NAME);
            if (stale != null) scoreboard.removeObjective(stale);
            objective = scoreboard.addObjective(OBJECTIVE_NAME, ObjectiveCriteria.DUMMY, title,
                    ObjectiveCriteria.RenderType.INTEGER);
            scoreboard.setDisplayObjective(Scoreboard.DISPLAY_SLOT_SIDEBAR, objective);
        }
        objective.setDisplayName(title);
        for (int i = 0; i < lines.size(); i++) {
            String entry = lineEntry(i);
            PlayerTeam team = teamFor(scoreboard, i, entry);
            team.setPlayerPrefix(Component.literal(lines.get(i)));
            Score score = scoreboard.getOrCreatePlayerScore(entry, objective);
            score.setScore(lines.size() - i);   // higher score sorts nearer the top
        }
    }

    void hide(MinecraftServer server) {
        clearStale(server);
    }

    /** Remove our objective and helper teams (also clears a copy left by a crashed session). */
    void clearStale(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();
        Objective stale = scoreboard.getObjective(OBJECTIVE_NAME);
        if (stale != null) scoreboard.removeObjective(stale);
        for (int i = 0; i < MAX_LINES; i++) {
            PlayerTeam team = scoreboard.getPlayerTeam(teamName(i));
            if (team != null) scoreboard.removePlayerTeam(team);
        }
        objective = null;
    }

    private PlayerTeam teamFor(Scoreboard scoreboard, int i, String entry) {
        PlayerTeam team = scoreboard.getPlayerTeam(teamName(i));
        if (team == null) {
            team = scoreboard.addPlayerTeam(teamName(i));
        }
        scoreboard.addPlayerToTeam(entry, team);
        return team;
    }

    private static String teamName(int i) {
        return "ru_line_" + i;
    }

    /** A unique, effectively-invisible entry name per row (a lone colour code renders as no glyph). */
    private static String lineEntry(int i) {
        return "§" + "0123456789abcdef".charAt(i % 16);
    }
}
