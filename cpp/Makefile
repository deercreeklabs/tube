NAME := tube
DLIB := lib$(NAME)
CXX := clang++

DEPS := src/tube_server.h src/tube_server.cpp
CPP_SHARED := -shared -fPIC
CPP_OPENSSL_OSX := -L/usr/local/opt/openssl/lib -I/usr/local/opt/openssl/include
CPP_OSX := -stdlib=libc++ -mmacosx-version-min=10.7 -undefined dynamic_lookup $(CPP_OPENSSL_OSX) -I/usr/local/include
CPPFLAGS := $(CPPFLAGS) -std=c++11 -O3 -I src

.PHONY: default
default: $(DEPS)
	@make `(uname -s)`

.PHONY: Linux
Linux:
	@$(CXX) $(CPPFLAGS) $(CFLAGS) $(CPP_SHARED) -s -o $(DLIB).so src/tube_server.cpp

.PHONY: Darwin
Darwin:
	@$(CXX) $(CPPFLAGS) $(CFLAGS) $(CPP_SHARED) $(CPP_OSX) -o $(DLIB).dylib src/tube_server.cpp

.PHONY: install
install:
	@make install`(uname -s)`

.PHONY: installLinux
installLinux:
	@if [ -d "/usr/lib64" ]; then cp $(DLIB).so /usr/lib64/; else cp $(DLIB).so /usr/lib/; fi
	@mkdir -p /usr/include/$(DLIB)
	@cp src/*.h /usr/include/$(DLIB)/

.PHONY: installDarwin
installDarwin:
	@cp $(DLIB).dylib /usr/local/lib/
	@mkdir -p /usr/local/include/$(DLIB)
	@cp src/*.h /usr/local/include/$(DLIB)/

.PHONY: clean
clean:
	@rm -f $(DLIB).so
	@rm -f $(DLIB).dylib
	@rm -rf bin

test: src/test_main.cpp default
	@mkdir -p bin
	@$(CXX) $(CPPFLAGS) $(CFLAGS) $(CPP_OSX) -L. -l$(NAME) -o bin/run_test $<
	@bin/run_test
