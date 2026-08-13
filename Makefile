# Get plugin version
PLUGIN_VERSION := $(shell grep 'version = ' build.gradle.kts | sed 's/.*"\(.*\)".*/\1/')

# Default target
all: clean build package

clean:
	./gradlew clean
	rm -rf release/

build:
	./gradlew buildPlugin

package:
	mkdir -p release
	cp build/distributions/jetbrain-psi-plugin-$(PLUGIN_VERSION).zip release/

versions:
	@echo "Plugin: v$(PLUGIN_VERSION)"

# ----------------------------
# Plugin version bumps
# ----------------------------

bump-plugin-patch:
	@set -e; \
	CUR=$(PLUGIN_VERSION); \
	MAJ=$$(echo $$CUR | cut -d. -f1); \
	MIN=$$(echo $$CUR | cut -d. -f2); \
	PAT=$$(echo $$CUR | cut -d. -f3); \
	NEW="$$MAJ.$$MIN.$$((PAT+1))"; \
	awk -v v="$$NEW" 'BEGIN{re= "^[[:space:]]*version[[:space:]]*=[[:space:]]*\".*\"[[:space:]]*$$"} { if ($$0 ~ re) print "version = \"" v "\""; else print $$0 }' build.gradle.kts > build.gradle.kts.tmp && mv build.gradle.kts.tmp build.gradle.kts; \
	echo "plugin-version: $$CUR -> $$NEW"

bump-plugin-minor:
	@set -e; \
	CUR=$(PLUGIN_VERSION); \
	MAJ=$$(echo $$CUR | cut -d. -f1); \
	MIN=$$(echo $$CUR | cut -d. -f2); \
	NEW="$$MAJ.$$((MIN+1)).0"; \
	awk -v v="$$NEW" 'BEGIN{re= "^[[:space:]]*version[[:space:]]*=[[:space:]]*\".*\"[[:space:]]*$$"} { if ($$0 ~ re) print "version = \"" v "\""; else print $$0 }' build.gradle.kts > build.gradle.kts.tmp && mv build.gradle.kts.tmp build.gradle.kts; \
	echo "plugin-version: $$CUR -> $$NEW"

bump-plugin-major:
	@set -e; \
	CUR=$(PLUGIN_VERSION); \
	MAJ=$$(echo $$CUR | cut -d. -f1); \
	NEW="$$((MAJ+1)).0.0"; \
	awk -v v="$$NEW" 'BEGIN{re= "^[[:space:]]*version[[:space:]]*=[[:space:]]*\".*\"[[:space:]]*$$"} { if ($$0 ~ re) print "version = \"" v "\""; else print $$0 }' build.gradle.kts > build.gradle.kts.tmp && mv build.gradle.kts.tmp build.gradle.kts; \
	echo "plugin-version: $$CUR -> $$NEW"

.PHONY: all clean build package versions bump-plugin-patch bump-plugin-minor bump-plugin-major
