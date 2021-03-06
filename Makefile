SPECFILE=$(shell find -maxdepth 1 -name \*.spec -exec basename {} \; )
REPOURL=git+ssh://git@gitlab.cern.ch:7999
# DB gitlab group
REPOPREFIX=/db

# Get all the package infos from the spec file
PKGVERSION=$(shell awk '/Version:/ { print $$2 }' ${SPECFILE})
PKGRELEASE=$(shell awk '/Release:/ { print $$2 }' ${SPECFILE} | sed -e 's/\%{?dist}//')
PKGNAME=$(shell awk '/Name:/ { print $$2 }' ${SPECFILE})
PKGID=$(PKGNAME)-$(PKGVERSION)
TARFILE=$(PKGID).tar.gz

sources:
	#tar cvzf $(TARFILE) --exclude-vcs --transform 's,^,$(PKGID)/,' *
	rm -rf /tmp/$(PKGID)
	mkdir /tmp/$(PKGID)
	cp -rv * /tmp/$(PKGID)/
	pwd ; ls -l
	cd /tmp ; tar --exclude .svn --exclude .git --exclude .gitkeep -czf $(TARFILE) $(PKGID)
	mv /tmp/$(TARFILE) .
	rm -rf /tmp/$(PKGID)

all:    sources

clean:
	rm $(TARFILE)

srpm:   all
	rpmbuild -bs --define '_sourcedir $(PWD)' ${SPECFILE}

rpm:    all
	rpmbuild -ba --define '_sourcedir $(PWD)' ${SPECFILE}

scratch:
	koji build db6 --nowait --scratch  ${REPOURL}${REPOPREFIX}/${PKGNAME}.git#master
	koji build db7 --nowait --scratch  ${REPOURL}${REPOPREFIX}/${PKGNAME}.git#master

build:
	koji build db6 --nowait ${REPOURL}${REPOPREFIX}/${PKGNAME}.git#master
	koji build db7 --nowait ${REPOURL}${REPOPREFIX}/${PKGNAME}.git#master
	
tag-qa:
	koji tag-build db6-qa $(PKGID)-$(PKGRELEASE).el6
	koji tag-build db7-qa $(PKGID)-$(PKGRELEASE).el7.cern

tag-stable:
	koji tag-build db6-stable $(PKGID)-$(PKGRELEASE).el6
	koji tag-build db7-stable $(PKGID)-$(PKGRELEASE).el7.cern	


