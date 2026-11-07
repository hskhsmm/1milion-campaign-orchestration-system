ANSIBLE_CONFIG := ./ansible.cfg
INVENTORY := inventory/localhost.yml
ANSIBLE_PLAYBOOK := ansible-playbook

.PHONY: help check-tools requirements env-status env-up env-down app-up app-down redis-exporter

help:
	@echo "Usage: make <target>"
	@echo ""
	@echo "Targets:"
	@echo "  check-tools     Check local CLI tools required by the playbooks"
	@echo "  requirements    Install Ansible Galaxy collections"
	@echo "  env-status      Print current AWS test environment status"
	@echo "  env-up          Start cost-aware test environment"
	@echo "  env-down        Stop cost-aware test environment"
	@echo "  app-up          Start app groups only using APP_LAYOUT"
	@echo "  app-down        Stop app groups after draining"
	@echo "  redis-exporter  Restart redis-exporter on terraform-mcp through SSM"

check-tools:
	@command -v ansible-playbook >/dev/null
	@command -v ansible-galaxy >/dev/null
	@command -v aws >/dev/null
	@command -v terraform >/dev/null
	@command -v jq >/dev/null
	@echo "Required tools are available."

requirements:
	ansible-galaxy collection install -r requirements.yml

env-status:
	ANSIBLE_CONFIG=$(ANSIBLE_CONFIG) $(ANSIBLE_PLAYBOOK) -i $(INVENTORY) playbooks/env-status.yml

env-up:
	ANSIBLE_CONFIG=$(ANSIBLE_CONFIG) $(ANSIBLE_PLAYBOOK) -i $(INVENTORY) playbooks/env-up.yml -e confirm_env_up=true

env-down:
	ANSIBLE_CONFIG=$(ANSIBLE_CONFIG) $(ANSIBLE_PLAYBOOK) -i $(INVENTORY) playbooks/env-down.yml -e confirm_env_down=true

app-up:
	ANSIBLE_CONFIG=$(ANSIBLE_CONFIG) $(ANSIBLE_PLAYBOOK) -i $(INVENTORY) playbooks/env-up.yml -e confirm_env_up=true --tags app-capacity

app-down:
	ANSIBLE_CONFIG=$(ANSIBLE_CONFIG) $(ANSIBLE_PLAYBOOK) -i $(INVENTORY) playbooks/env-down.yml -e confirm_env_down=true --tags app-capacity

redis-exporter:
	ANSIBLE_CONFIG=$(ANSIBLE_CONFIG) $(ANSIBLE_PLAYBOOK) -i $(INVENTORY) playbooks/restart-redis-exporter.yml
