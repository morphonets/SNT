FROM gitpod/workspace-full:latest

RUN bash -c ". /home/gitpod/.sdkman/bin/sdkman-init.sh && \
    sdk install java 8.0.345.fx-zulu && \
    sdk default java 8.0.345.fx-zulu"
