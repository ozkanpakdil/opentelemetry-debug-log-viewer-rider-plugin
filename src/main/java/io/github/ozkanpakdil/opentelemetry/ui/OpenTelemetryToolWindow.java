package io.github.ozkanpakdil.opentelemetry.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.codeInsight.folding.CodeFoldingManager;
import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.actions.AbstractToggleUseSoftWrapsAction;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.editor.impl.softwrap.SoftWrapAppliancePlaces;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.ui.LanguageTextField;
import com.intellij.ui.components.fields.ExtendableTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.unscramble.AnalyzeStacktraceUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.jetbrains.rd.util.lifetime.Lifetime;
import io.github.ozkanpakdil.opentelemetry.OpentelemetrySession;
import io.github.ozkanpakdil.opentelemetry.Telemetry;
import io.github.ozkanpakdil.opentelemetry.settings.AppSettingState;
import io.github.ozkanpakdil.opentelemetry.ui.components.AutoScrollToTheEndToolbarAction;
import io.github.ozkanpakdil.opentelemetry.ui.components.ClearApplicationInsightsLogToolbarAction;
import io.github.ozkanpakdil.opentelemetry.ui.components.OptionsToolbarAction;
import io.github.ozkanpakdil.opentelemetry.ui.components.ToggleCaseInsensitiveSearchToolbarAction;
import io.github.ozkanpakdil.opentelemetry.ui.renderers.TelemetryDateRender;
import io.github.ozkanpakdil.opentelemetry.ui.renderers.TelemetryRender;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class OpenTelemetryToolWindow {
    @NotNull
    private JPanel mainPanel;
    @NotNull
    private JBTable logsTable;
    @NotNull
    private JSplitPane splitPane;
    @NotNull
    private ExtendableTextField filter;
    @NotNull
    private JScrollPane logsScrollPane;
    @NotNull
    private ActionToolbarImpl toolbar;
    @NotNull
    private JComponent editorPanel;

    @NotNull
    private final Project project;
    @NotNull
    private final TelemetryRender telemetryRender;
    @NotNull
    private final OpentelemetrySession opentelemetrySession;
    private final Lifetime lifetime;

    @NotNull
    private Editor editor;
    @NotNull
    private final TelemetryTableModel telemetryTableModel;
    @NotNull
    private final ArrayList<JLabel> telemetryTypesCounter = new ArrayList<>();
    @NotNull
    private Document jsonPreviewDocument;
    private boolean autoScrollToTheEnd;
    private final TextConsoleBuilder builder;
    Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public OpenTelemetryToolWindow(
            @NotNull OpentelemetrySession opentelemetrySession,
            @NotNull Project project,
            Lifetime lifetime) {
        this.project = project;
        this.opentelemetrySession = opentelemetrySession;
        this.lifetime = lifetime;

        splitPane.setDividerLocation(0.5);
        splitPane.setResizeWeight(0.5);
        try {
            ReadAction.nonBlocking(() -> {
                return CodeFoldingManager.getInstance(ProjectManager.getInstance().getDefaultProject())
                        .buildInitialFoldings(jsonPreviewDocument);
            }).submit(AppExecutorUtil.getAppExecutorService()).get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }

        this.telemetryRender = new TelemetryRender(lifetime);
        logsTable.setDefaultRenderer(Telemetry.class, telemetryRender);
        logsTable.setDefaultRenderer(Date.class, new TelemetryDateRender());
        logsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        telemetryTableModel = new TelemetryTableModel();
        logsTable.setModel(telemetryTableModel);
        logsTable.getColumnModel().getColumn(0).setPreferredWidth(90);
        logsTable.getColumnModel().getColumn(0).setMaxWidth(130);
        logsTable.getColumnModel().getColumn(1).setPreferredWidth(90);
        logsTable.getColumnModel().getColumn(1).setMaxWidth(130);
        logsTable.getTableHeader().setUI(null);

        filter.setExtensions(new ClearTextFieldExtension(filter));

        filter.addKeyListener(new KeyListener() {
            @Override
            public void keyTyped(KeyEvent e) {
            }

            @Override
            public void keyPressed(KeyEvent e) {
                OpenTelemetryToolWindow.this.opentelemetrySession.updateFilter(filter.getText());
            }

            @Override
            public void keyReleased(KeyEvent e) {
                OpenTelemetryToolWindow.this.opentelemetrySession.updateFilter(filter.getText());
            }
        });

        logsTable.getSelectionModel().addListSelectionListener(e -> {
            selectTelemetry(telemetryTableModel.getRow(logsTable.getSelectedRow()));
        });

        builder = TextConsoleBuilderFactory.getInstance().createBuilder(project);
        builder.filters(AnalyzeStacktraceUtil.EP_NAME.getExtensions(project));

        AppSettingState.getInstance().showFilteredIndicator.advise(lifetime, (v) -> {
            this.logsTable.invalidate();
            this.logsTable.repaint();
            return Unit.INSTANCE;
        });
    }

    private void selectTelemetry(@Nullable Telemetry telemetry) {
        updateJsonPreview(gson.toJson(telemetry == null ? "" : telemetry.getData()));

    }

    private void updateJsonPreview(String text) {
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n");

        for (String line : lines) {
            int leadingSpaces = countLeadingSpaces(line);

            if (leadingSpaces > 0) {
                int tabs = leadingSpaces / 2;
                sb.append("\t".repeat(tabs))
                        .append(line.substring(leadingSpaces));
            } else {
                sb.append(line);
            }

            sb.append('\n');
        }

        ApplicationManager.getApplication().runWriteAction(() -> {
            jsonPreviewDocument.setText(sb.toString());
        });

        CodeFoldingManager.getInstance(ProjectManager.getInstance().getDefaultProject())
                .updateFoldRegions(editor);
    }

    private int countLeadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    @NotNull
    public JPanel getContent() {
        return mainPanel;
    }

    public void setTelemetries(
            @NotNull List<Telemetry> telemetries,
            @NotNull List<Telemetry> visibleTelemetries
    ) {
        telemetryTableModel.setRows(visibleTelemetries);
    }

    public void addTelemetry(
            int index,
            @NotNull Telemetry telemetry,
            boolean visible,
            boolean shouldScroll
    ) {
        if (visible) {
            if (index != -1)
                telemetryTableModel.addRow(index, telemetry);
            else
                telemetryTableModel.addRow(telemetry);
            SwingUtilities.invokeLater(() -> {
                if (autoScrollToTheEnd && shouldScroll) {
                    performAutoScrollToTheEnd();
                }
            });
        }
    }

    private void performAutoScrollToTheEnd() {
        logsTable.scrollRectToVisible(
                logsTable.getCellRect(telemetryTableModel.getRowCount() - 1, 0, true));
    }

    private void createUIComponents() {
        toolbar = createToolbar();
        toolbar.setTargetComponent(mainPanel);
        toolbar.setVisible(false);

        jsonPreviewDocument = new LanguageTextField.SimpleDocumentCreator().createDocument("", com.intellij.lang.Language.findLanguageByID("json"), project);
        editor = EditorFactory.getInstance().createViewer(jsonPreviewDocument, project, EditorKind.MAIN_EDITOR);
        if (editor instanceof EditorEx) {
            ((EditorEx) editor).setHighlighter(
                    EditorHighlighterFactory.getInstance().createEditorHighlighter(project, "json"));
            ((EditorEx) editor).getFoldingModel().setFoldingEnabled(true);
        }
        editor.getSettings().setIndentGuidesShown(true);
        editor.getSettings().setAdditionalLinesCount(3);
        editor.getSettings().setFoldingOutlineShown(true);
        editor.getSettings().setUseSoftWraps(
                PropertiesComponent.getInstance().getBoolean("io.github.ozkanpakdil.opentelemetry.useSoftWrap"));

        editorPanel = editor.getComponent();
    }

    @NotNull
    private ActionToolbarImpl createToolbar() {
        final DefaultActionGroup actionGroup = new DefaultActionGroup();

        actionGroup.add(new OptionsToolbarAction(() -> toolbar));
        actionGroup.addSeparator();

        autoScrollToTheEnd = PropertiesComponent.getInstance()
                .getBoolean("io.github.ozkanpakdil.opentelemetry.autoScrollToTheEnd");

        actionGroup.add(new AutoScrollToTheEndToolbarAction(this::acceptScrollRoEnd, autoScrollToTheEnd));

        actionGroup.add(new ToggleCaseInsensitiveSearchToolbarAction());

        actionGroup.add(new AbstractToggleUseSoftWrapsAction(SoftWrapAppliancePlaces.PREVIEW, false) {
            {
                ActionUtil.copyFrom(this, "EditorToggleUseSoftWraps");
            }

            @Override
            public void setSelected(@NotNull AnActionEvent e, boolean state) {
                super.setSelected(e, state);
                PropertiesComponent.getInstance().setValue("io.github.ozkanpakdil.opentelemetry.useSoftWrap", state);
            }

            @NotNull
            @Override
            protected Editor getEditor(@NotNull AnActionEvent e) {
                return editor;
            }
        });

        actionGroup.add(new ClearApplicationInsightsLogToolbarAction() {
            @Override
            public void actionPerformed(@NotNull AnActionEvent anActionEvent) {
                opentelemetrySession.clear();
            }
        });

        return new ActionToolbarImpl("Opentelemetry", actionGroup, false);
    }

    private void acceptScrollRoEnd(Boolean selected) {
        autoScrollToTheEnd = selected;
        PropertiesComponent.getInstance().setValue("io.github.ozkanpakdil.opentelemetry.autoScrollToTheEnd", selected);
        if (autoScrollToTheEnd) {
            performAutoScrollToTheEnd();
        }
    }
}

