FROM gitpod/workspace-full:latest

RUN bash -c ". /home/gitpod/.sdkman/bin/sdkman-init.sh && \
    sdk install java 11.0.20.1.fx-zulu && \
    sdk default java 11.0.20.1.fx-zulu"
