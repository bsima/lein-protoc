
.PHONY: test
test:
	lein test

.PHONY: clean
clean:
	lein clean
	rm -rf test/target test/src
