package com.teggr.codeagent.docker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContainerLaunchTest {

    @Test
    void devContainerLaunchValuesUseEncodedContainerId() {
        ContainerLaunch launch = new ContainerLaunch("05159a31cf260000000000000000000000000000000000000000000000000000", 4321);

        assertThat(launch.devContainerUri())
                .isEqualTo("vscode://vscode-remote/attached-container+303531353961333163663236/workspace?windowId=_blank");
        assertThat(launch.devContainerCliCommand())
                .isEqualTo("code --new-window --folder-uri vscode-remote://attached-container+303531353961333163663236/workspace");
    }
}