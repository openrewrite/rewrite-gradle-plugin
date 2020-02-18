package org.gradle.rewrite;

import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.internal.logging.text.StyledTextOutputFactory;
import org.gradle.internal.service.ServiceRegistry;

public class StyledTextService {
    private StyledTextOutput textOutput;

    public StyledTextService(ServiceRegistry registry) {
        this.textOutput = registry.get(StyledTextOutputFactory.class).create("rewrite");
    }

    public StyledTextOutput withStyle(Styling style) {
        switch (style) {
            case Bold:
                return textOutput.withStyle(StyledTextOutput.Style.UserInput);
            case Green:
                return textOutput.withStyle(StyledTextOutput.Style.Identifier);
            case Yellow:
                return textOutput.withStyle(StyledTextOutput.Style.Description);
            case Red:
            default:
                return textOutput.withStyle(StyledTextOutput.Style.Failure);
        }
    }

    public StyledTextService text(Object o) {
        textOutput.text(o);
        return this;
    }

    public StyledTextService println(Object o) {
        textOutput.println(o);
        return this;
    }
}
