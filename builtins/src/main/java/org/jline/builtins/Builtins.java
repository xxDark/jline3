/*
 * Copyright (c) 2002-2019, the original author or authors.
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.builtins;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.jline.builtins.Completers.FilesCompleter;
import org.jline.builtins.Completers.OptDesc;
import org.jline.builtins.Completers.OptionCompleter;
import org.jline.builtins.Completers.SystemCompleter;
import org.jline.builtins.Options.HelpException;
import org.jline.builtins.Widgets.ArgDesc;
import org.jline.builtins.Widgets.CmdDesc;
import org.jline.reader.*;
import org.jline.reader.LineReader.Option;
import org.jline.reader.impl.completer.ArgumentCompleter;
import org.jline.reader.impl.completer.NullCompleter;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

/**
 * Builtins: create tab completers, execute and create descriptions for builtins commands.
 *
 * @author <a href="mailto:matti.rintanikkola@gmail.com">Matti Rinta-Nikkola</a>
 */
public class Builtins implements CommandRegistry {
    public enum Command {NANO
                       , LESS
                       , HISTORY
                       , WIDGET
                       , KEYMAP
                       , SETOPT
                       , SETVAR
                       , UNSETOPT
                       , TTOP};
    private ConfigurationPath configPath;
    private final Function<String, Widget> widgetCreator;
    private final Supplier<Path> workDir;
    private Map<Command,String> commandName = new HashMap<>();
    private Map<String,Command> nameCommand = new HashMap<>();
    private Map<String,String> aliasCommand = new HashMap<>();
    private final Map<Command,CommandMethods> commandExecute = new HashMap<>();
    private Map<Command,List<String>> commandInfo = new HashMap<>();
    private LineReader reader;
    private Exception exception;

    public Builtins(Path workDir, ConfigurationPath configPath, Function<String, Widget> widgetCreator) {
        this(null, () -> workDir, configPath, widgetCreator);
    }

    public Builtins(Set<Command> commands, Path workDir, ConfigurationPath configpath, Function<String, Widget> widgetCreator) {
        this(commands, () -> workDir, configpath, widgetCreator);
    }

    public Builtins(Supplier<Path> workDir, ConfigurationPath configPath, Function<String, Widget> widgetCreator) {
        this(null, workDir, configPath, widgetCreator);
    }

    public Builtins(Set<Command> commands, Supplier<Path> workDir, ConfigurationPath configpath, Function<String, Widget> widgetCreator) {
        this.configPath = configpath;
        this.widgetCreator = widgetCreator;
        this.workDir = workDir;
        Set<Command> cmds = new HashSet<>();
        if (commands == null) {
            cmds = new HashSet<>(EnumSet.allOf(Command.class));
        } else {
            cmds = new HashSet<>(commands);
        }
        for (Command c: cmds) {
            commandName.put(c, c.name().toLowerCase());
        }
        doNameCommand();
        commandExecute.put(Command.NANO, new CommandMethods(this::nano, this::nanoCompleter));
        commandExecute.put(Command.LESS, new CommandMethods(this::less, this::lessCompleter));
        commandExecute.put(Command.HISTORY, new CommandMethods(this::history, this::historyCompleter));
        commandExecute.put(Command.WIDGET, new CommandMethods(this::widget, this::widgetCompleter));
        commandExecute.put(Command.KEYMAP, new CommandMethods(this::keymap, this::keymapCompleter));
        commandExecute.put(Command.SETOPT, new CommandMethods(this::setopt, this::setoptCompleter));
        commandExecute.put(Command.SETVAR, new CommandMethods(this::setvar, this::setvarCompleter));
        commandExecute.put(Command.UNSETOPT, new CommandMethods(this::unsetopt, this::unsetoptCompleter));
        commandExecute.put(Command.TTOP, new CommandMethods(this::ttop, this::ttopCompleter));
    }

    public Set<String> commandNames() {
        return nameCommand.keySet();
    }

    public Map<String, String> commandAliases() {
        return aliasCommand;
    }

    public List<String> commandInfo(String command) {
        if (!commandInfo.containsKey(command(command))) {
            commandOptions(command);
        }
        return commandInfo.get(command(command));
    }

    private void doNameCommand() {
        nameCommand = commandName.entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
    }

    public void setLineReader(LineReader reader) {
        this.reader = reader;
    }

    public void rename(Command command, String newName) {
        if (nameCommand.containsKey(newName)) {
            throw new IllegalArgumentException("Duplicate command name!");
        } else if (!commandName.containsKey(command)) {
            throw new IllegalArgumentException("Command does not exists!");
        }
        commandName.put(command, newName);
        doNameCommand();
    }

    public void alias(String alias, String command) {
        if (!nameCommand.keySet().contains(command)) {
            throw new IllegalArgumentException("Command does not exists!");
        }
        aliasCommand.put(alias, command);
    }

    public boolean hasCommand(String name) {
        if (nameCommand.containsKey(name) || aliasCommand.containsKey(name)) {
            return true;
        }
        return false;
    }

    public SystemCompleter compileCompleters() {
        SystemCompleter out = new SystemCompleter();
        for (Map.Entry<Command, String> entry: commandName.entrySet()) {
            out.add(entry.getValue(), commandExecute.get(entry.getKey()).compileCompleter().apply(entry.getValue()));
        }
        out.addAliases(aliasCommand);
        return out;
    }

    private Command command(String name) {
        Command out = null;
        if (!hasCommand(name)) {
            throw new IllegalArgumentException("Command does not exists!");
        }
        if (aliasCommand.containsKey(name)) {
            name = aliasCommand.get(name);
        }
        if (nameCommand.containsKey(name)) {
            out = nameCommand.get(name);
        } else {
            throw new IllegalArgumentException("Command does not exists!");
        }
        return out;
    }

    private void execute(String command, List<String> args) throws Exception {
        execute(command, args, System.in, System.out, System.err);
    }

    public void execute(String command, List<String> args, InputStream in, PrintStream out, PrintStream err) throws Exception {
        execute(command, args.toArray(new String[0]), in, out, err);
    }

    public void execute(String command, String[] args, InputStream in, PrintStream out, PrintStream err) throws Exception {
        exception = null;
        commandExecute.get(command(command)).execute().accept(new CommandInput(args, in, out, err));
        if (exception != null) {
            throw exception;
        }
    }

    public CmdDesc commandDescription(String command) {
        CmdDesc out = null;
        List<String> args = Arrays.asList("--help");
        try {
            execute(command, args);
        } catch (HelpException e) {
            List<AttributedString> main = new ArrayList<>();
            Map<String, List<AttributedString>> options = new HashMap<>();
            String[] msg = e.getMessage().replaceAll("\r\n", "\n").replaceAll("\r", "\n").split("\n");
            String prevOpt = null;
            boolean mainDone = false;
            boolean start = false;
            for (String s: msg) {
                if (!start) {
                    if (s.trim().startsWith("Usage: ")) {
                        s = s.split("Usage:")[1];
                        start = true;
                    } else {
                        continue;
                    }
                }
                if (s.matches("^\\s+-.*$")) {
                    mainDone = true;
                    int ind = s.lastIndexOf("  ");
                    if (ind > 0) {
                        String o = s.substring(0, ind);
                        String d = s.substring(ind);
                        if (o.trim().length() > 0) {
                            prevOpt = o.trim();
                            options.put(prevOpt, new ArrayList<>(Arrays.asList(highlightComment(d.trim()))));
                        }
                    }
                } else if (s.matches("^[\\s]{20}.*$") && prevOpt != null && options.containsKey(prevOpt)) {
                    int ind = s.lastIndexOf("  ");
                    if (ind > 0) {
                        options.get(prevOpt).add(highlightComment(s.substring(ind).trim()));
                    }
                } else {
                    prevOpt = null;
                }
                if (!mainDone) {
                    main.add(HelpException.highlightSyntax(s.trim(), HelpException.defaultStyle()));
                }
            }
            out = new CmdDesc(main, ArgDesc.doArgNames(Arrays.asList("")), options);
        } catch (Exception e) {

        }
        return out;
    }

    private List<OptDesc> commandOptions(String command) {
        List<OptDesc> out = new ArrayList<>();
        List<String> args = Arrays.asList("--help");
        try {
            execute(command, args);
        } catch (HelpException e) {
            List<String> info = new ArrayList<>();
            String[] msg = e.getMessage().replaceAll("\r\n", "\n").replaceAll("\r", "\n").split("\n");
            boolean start = false;
            boolean first = true;
            for (String s: msg) {
                if (!start) {
                    if (s.trim().startsWith("Usage: ")) {
                        s = s.split("Usage:")[1];
                        start = true;
                    } else {
                        if (first && s.contains(" - ")) {
                            info.add(s.substring(s.indexOf(" - ") + 3).trim());
                        } else {
                            info.add(s.trim());
                        }
                        first = false;
                        continue;
                    }
                }
                if (s.matches("^\\s+-.*$")) {
                    int ind = s.lastIndexOf("  ");
                    if (ind > 0) {
                        String[] op = s.substring(0, ind).trim().split("\\s+");
                        String d = s.substring(ind).trim();
                        String so = null;
                        String lo = null;
                        if (op.length == 1) {
                            if (op[0].startsWith("--")) {
                                lo = op[0];
                            } else {
                                so = op[0];
                            }
                        } else {
                            so = op[0];
                            lo = op[1];
                        }
                        lo = lo == null ? lo : lo.split("=")[0];
                        out.add(new OptDesc(so, lo, d));
                    }
                }
            }
            commandInfo.put(command(command), info);
        } catch (Exception e) {

        }
        return out;
    }

    private AttributedString highlightComment(String comment) {
        return HelpException.highlightComment(comment, HelpException.defaultStyle());
    }

    private Terminal terminal() {
        return reader.getTerminal();
    }

    private void less(CommandInput input) {
        try {
            Commands.less(terminal(), input.in(), input.out(), input.err(), workDir.get(), input.args());
        } catch (Exception e) {
            this.exception = e;
        }
    }

    private void nano(CommandInput input) {
        try {
            Commands.nano(terminal(), input.out(), input.err(), workDir.get(), input.args(), configPath);
        } catch (Exception e) {
            this.exception = e;
        }
    }

    private void history(CommandInput input) {
        try {
            Commands.history(reader, input.out(), input.err(), workDir.get(), input.args());
        } catch (Exception e) {
            this.exception = e;
        }
    }

    private void widget(CommandInput input) {
        try {
            Commands.widget(reader, input.out(), input.err(), widgetCreator, input.args());
        } catch (Exception e) {
            this.exception = e;
        }
    }

    private void keymap(CommandInput input) {
        try {
            Commands.keymap(reader, input.out(), input.err(), input.args());
        } catch (Exception e) {
            this.exception = e;
        }
    }

    private void setopt(CommandInput input) {
        try {
            Commands.setopt(reader, input.out(), input.err(), input.args());
        } catch (Exception e) {
            this.exception = e;
        }
    }

    private void setvar(CommandInput input) {
        try {
            Commands.setvar(reader, input.out(), input.err(), input.args());
        } catch (Exception e) {
            this.exception = e;
        }
    }

    private void unsetopt(CommandInput input) {
        try {
            Commands.unsetopt(reader, input.out(), input.err(), input.args());
        } catch (Exception e) {
            this.exception = e;
        }
    }

    private void ttop(CommandInput input) {
        try {
            TTop.ttop(terminal(), input.out(), input.err(), input.args());
        } catch (Exception e) {
            this.exception = e;
        }
    }

    private List<String> unsetOptions(boolean set) {
        List<String> out = new ArrayList<>();
        for (Option option : Option.values()) {
            if (set == (reader.isSet(option) == option.isDef())) {
                out.add((option.isDef() ? "no-" : "") + option.toString().toLowerCase().replace('_', '-'));
            }
        }
        return out;
    }

    private Set<String> allWidgets() {
        Set<String> out = new HashSet<>();
        for (String s: reader.getWidgets().keySet()) {
            out.add(s);
            out.add(reader.getWidgets().get(s).toString());
        }
        return out;
    }

    private List<Completer> nanoCompleter(String name) {
        List<Completer> completers = new ArrayList<>();
        completers.add(new ArgumentCompleter(NullCompleter.INSTANCE
                                           , new OptionCompleter(new FilesCompleter(workDir)
                                                               , this::commandOptions
                                                               , 1)
                                            ));
        return completers;
    }

    private List<Completer> lessCompleter(String name) {
        List<Completer> completers = new ArrayList<>();
        completers.add(new ArgumentCompleter(NullCompleter.INSTANCE
                                           , new OptionCompleter(new FilesCompleter(workDir)
                                                               , this::commandOptions
                                                               , 1)
                                       ));
        return completers;
    }

    private List<Completer> historyCompleter(String name) {
        List<Completer> completers = new ArrayList<>();
        List<OptDesc> optDescs = commandOptions(commandName.get(Command.HISTORY));
        for (OptDesc o : optDescs) {
            if (o.shortOption() != null && (o.shortOption().equals("-A") || o.shortOption().equals("-W")
                                        || o.shortOption().equals("-R"))) {
                o.setValueCompleter(new FilesCompleter(workDir));
            }
        }
        completers.add(new ArgumentCompleter(NullCompleter.INSTANCE
                                            , new OptionCompleter(NullCompleter.INSTANCE
                                                                , optDescs
                                                                , 1)
                                       ));
        return completers;
    }

    private List<Completer> widgetCompleter(String name) {
        List<Completer> completers = new ArrayList<>();
        List<OptDesc> optDescs = commandOptions(commandName.get(Command.WIDGET));
        Candidate aliasOption = new Candidate("-A", "-A", null, null, null, null, true);
        Iterator<OptDesc> i = optDescs.iterator();
        while (i.hasNext()) {
            OptDesc o = i.next();
            if (o.shortOption() != null) {
                if (o.shortOption().equals("-D")) {
                    o.setValueCompleter(new StringsCompleter(() -> reader.getWidgets().keySet()));
                } else if (o.shortOption().equals("-A")) {
                    aliasOption = new Candidate(o.shortOption(), o.shortOption(), null, o.description(), null, null, true);
                    i.remove();
                }
            }
        }
        completers.add(new ArgumentCompleter(NullCompleter.INSTANCE
                                            , new OptionCompleter(NullCompleter.INSTANCE
                                                                , optDescs
                                                                , 1)
                                       ));
        completers.add(new ArgumentCompleter(NullCompleter.INSTANCE
                     , new StringsCompleter(aliasOption), new StringsCompleter(() -> allWidgets())
                     , new StringsCompleter(() -> reader.getWidgets().keySet()), NullCompleter.INSTANCE));
        return completers;
    }

    private List<Completer> keymapCompleter(String name) {
        List<Completer> completers = new ArrayList<>();
        completers.add(new ArgumentCompleter(NullCompleter.INSTANCE
                                            , new OptionCompleter(NullCompleter.INSTANCE
                                                                , this::commandOptions
                                                                , 1)
                        ));
        return completers;
    }

    private List<Completer> setvarCompleter(String name) {
        List<Completer> completers = new ArrayList<>();
        completers.add(new ArgumentCompleter(NullCompleter.INSTANCE
                     , new StringsCompleter(() -> reader.getVariables().keySet()), NullCompleter.INSTANCE));
        return completers;
    }

    private List<Completer> setoptCompleter(String name) {
        List<Completer> completers = new ArrayList<>();
        completers.add(new ArgumentCompleter(NullCompleter.INSTANCE
                     , new StringsCompleter(() -> unsetOptions(true))));
        return completers;
    }

    private List<Completer> unsetoptCompleter(String name) {
        List<Completer> completers = new ArrayList<>();
        completers.add(new ArgumentCompleter(NullCompleter.INSTANCE
                     , new StringsCompleter(() -> unsetOptions(false))));
        return completers;
    }

    private List<Completer> ttopCompleter(String name) {
        List<Completer> completers = new ArrayList<>();
        completers.add(new ArgumentCompleter(NullCompleter.INSTANCE
                                            , new OptionCompleter(NullCompleter.INSTANCE
                                                                , this::commandOptions
                                                                , 1)
                                    ));
        return completers;
    }

    public static class CommandInput{
        String[] args;
        InputStream in;
        PrintStream out;
        PrintStream err;

        public CommandInput(String[] args) {
            this(args, null, null, null);
        }

        public CommandInput(String[] args, InputStream in, PrintStream out, PrintStream err) {
            this.args = args;
            this.in = in;
            this.out = out;
            this.err = err;
        }

        public String[] args() {
            return args;
        }

        public InputStream in() {
            return in;
        }

        public PrintStream out() {
            return out;
        }

        public PrintStream err() {
            return err;
        }

    }

    public static class CommandMethods {
        Consumer<CommandInput> execute;
        Function<String, List<Completer>> compileCompleter;

        public CommandMethods(Consumer<CommandInput> execute,  Function<String, List<Completer>> compileCompleter) {
            this.execute = execute;
            this.compileCompleter = compileCompleter;
        }

        public Consumer<CommandInput> execute() {
            return execute;
        }

        public Function<String, List<Completer>> compileCompleter() {
            return compileCompleter;
        }
    }

}

