#!/usr/bin/env bash
# Source after changing to the project directory. Never install a development
# framework snapshot in the user's shared Maven repository.
if [[ -z "${WORDPROCESSOR_MAVEN_REPO:-}" ]]; then
  if [[ -f .local/maven-repo-path ]]; then
    IFS= read -r WORDPROCESSOR_MAVEN_REPO < .local/maven-repo-path
  else
    WORDPROCESSOR_MAVEN_REPO="$PWD/.local/maven-repository"
  fi
fi
case "$WORDPROCESSOR_MAVEN_REPO" in
  "$HOME/.m2"|"$HOME/.m2/"*) echo 'The shared ~/.m2 repository is forbidden for this build.' >&2; exit 1 ;;
  /*) ;;
  *) echo 'WORDPROCESSOR_MAVEN_REPO must be an absolute, project-specific path.' >&2; exit 1 ;;
esac
# Require callers to use the single configuration variable, so a later Maven
# command cannot accidentally fall back to another repository.
for cn1_arg in "$@"; do
  case "$cn1_arg" in
    -Dmaven.repo.local=*)
      if [[ "${cn1_arg#*=}" != "$WORDPROCESSOR_MAVEN_REPO" ]]; then
        echo 'Set WORDPROCESSOR_MAVEN_REPO instead of overriding maven.repo.local.' >&2; exit 1
      fi ;;
  esac
done
export WORDPROCESSOR_MAVEN_REPO
