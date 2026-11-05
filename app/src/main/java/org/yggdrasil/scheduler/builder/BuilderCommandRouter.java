package org.yggdrasil.scheduler.builder;

/**
 * A deliberately small, deterministic command router.
 * It invokes only local capabilities that are implemented in this app.
 */
public final class BuilderCommandRouter {
    public enum Action { RECORD_CONTINUITY_NOTE, CHECK_JOURNAL, UNSUPPORTED }

    public static final class Command {
        public final Action action;
        public final String argument;
        public final String explanation;
        private Command(Action action, String argument, String explanation) {
            this.action = action; this.argument = argument; this.explanation = explanation;
        }
    }

    public Command route(String instruction) {
        if (instruction == null || instruction.trim().isEmpty())
            return new Command(Action.UNSUPPORTED, "", "Please enter an instruction.");
        String value = instruction.trim();
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        String[] notePrefixes = {"remember that ", "remember ", "record note ", "save note ", "tell muninn "};
        for (String prefix : notePrefixes) {
            if (lower.startsWith(prefix)) {
                String note = value.substring(prefix.length()).trim();
                if (!note.isEmpty())
                    return new Command(Action.RECORD_CONTINUITY_NOTE, note, "Record a local continuity note.");
            }
        }
        if (lower.equals("check journal") || lower.equals("check memory")
                || lower.equals("verify memory") || lower.equals("memory status"))
            return new Command(Action.CHECK_JOURNAL, "", "Verify the local Builder journal.");
        return new Command(Action.UNSUPPORTED, "",
                "That capability is not connected yet. I can record a continuity note or check the local journal; other actions are not executed.");
    }
}
